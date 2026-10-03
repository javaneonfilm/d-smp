// CartZone zone wall, written for cartzone.vip.
//
// The plugin (ZoneWall.java) puts an item display with item_model cartzone:zone_wall in the middle
// of the zone. The model is one flat square, side = zone diameter, painted in a marker colour.
// The vertex shader spots that colour and stretches the square over the whole screen; the
// fragment shader then casts a ray per pixel at an upright cylinder through the square's corners
// and paints the wall where it hits, writing the hit's depth so terrain still covers it.
//
// Needs ProjMat and ModelViewMat, so import projection.glsl and dynamictransforms.glsl first.

const float CZ_WALL_HEIGHT = 330.0;              // blocks above the square
const float CZ_WALL_VIEW = 128.0;               // how far away the wall can be seen (8 chunks)
const ivec4 CZ_WALL_MARKER = ivec4(204, 18, 52, 251);

bool cz_is_wall(vec4 texel) {
    return ivec4(texel * 255.0 + 0.5) == CZ_WALL_MARKER;
}

// Corner k of the square goes to a corner of the screen. Always counter-clockwise, so the
// stretched square is never back-face culled, whichever way the model faces.
vec2 cz_screen_corner(int k) {
    if (k == 0) return vec2(-1.0, -1.0);
    if (k == 1) return vec2(1.0, -1.0);
    if (k == 2) return vec2(1.0, 1.0);
    return vec2(-1.0, 1.0);
}

// Vertex k of the square: passes corners 0 and 2 (opposite each other, and in both triangles of
// the quad) to the fragment shader, plus the camera ray through this screen corner.
vec4 cz_wall_vertex(vec3 pos, int k, out vec4 p0, out vec4 p2, out vec3 farPoint, out vec3 origin) {
    p0 = k == 0 ? vec4(pos, 1.0) : vec4(0.0);
    p2 = k == 2 ? vec4(pos, 1.0) : vec4(0.0);
    vec2 ndc = cz_screen_corner(k);
    mat4 invView = inverse(ModelViewMat);
    vec4 far = inverse(ProjMat) * vec4(ndc, 1.0, 1.0);
    farPoint = (invView * vec4(far.xyz / far.w, 1.0)).xyz;
    origin = (invView * vec4(0.0, 0.0, 0.0, 1.0)).xyz;
    return vec4(ndc, 0.0, 1.0);
}

// Pointy-top hexagon grid where a cell is 1 wide. Returns the offset from the cell centre (xy)
// and the cell's axial coordinates (zw), found by rounding cube coordinates.
vec4 cz_hex_cell(vec2 p) {
    const float R = 0.57735027;                      // corner radius of a hexagon 1 wide
    float qf = (0.57735027 * p.x - 0.33333333 * p.y) / R;
    float rf = (0.66666667 * p.y) / R;
    vec3 c = vec3(qf, rf, -qf - rf);
    vec3 rc = floor(c + 0.5);
    vec3 d = abs(rc - c);
    if (d.x > d.y && d.x > d.z) rc.x = -rc.y - rc.z;
    else if (d.y > d.z) rc.y = -rc.x - rc.z;
    vec2 centre = vec2(R * (1.7320508 * rc.x + 0.8660254 * rc.y), R * 1.5 * rc.y);
    return vec4(p - centre, rc.xy);
}

// 0 in the middle of a cell, 0.5 on its edge.
float cz_hex_dist(vec2 v) {
    v = abs(v);
    return max(v.x, dot(v, vec2(0.5, 0.8660254)));
}

float cz_rand(vec2 id) {
    return fract(sin(dot(id, vec2(41.37, 289.11))) * 15731.743);
}

// One layer of hexagons, cell width w blocks. aa = edge softness in cell units.
vec4 cz_hex_layer(float angle, float radius, float h, float w, float aa, float time) {
    float cols = max(6.0, floor(6.2831853 * radius / w + 0.5));   // whole cells around the ring
    vec4 cell = cz_hex_cell(vec2(angle * cols, h / w));
    float e = cz_hex_dist(cell.xy);
    float row = cell.w;
    float r = cz_rand(cell.zw + vec2(0.0, 0.0));

    // The lit middle of each cell breathes, in a wave that runs up the wall.
    float breathe = sin(time * 1.7 - row * 0.55 + r * 0.8);
    float inner = 0.3 + 0.05 * breathe;

    vec3 bg = vec3(0.5, 0.82, 1.0);
    vec3 lit = vec3(0.62, 0.9, 1.0);
    vec3 rim = vec3(0.3, 0.62, 0.85);
    vec3 edge = vec3(0.8, 0.98, 1.0);

    float inside = 1.0 - smoothstep(inner - aa, inner + aa, e);
    vec3 col = mix(bg, lit, inside);
    float a = mix(0.05, 0.1, inside);
    // a darker band just outside the lit middle
    float band = smoothstep(inner, inner + 0.08, e) * (1.0 - smoothstep(0.4, 0.47, e));
    col = mix(col, rim, band * 0.35);
    a += band * 0.03;
    // thin light ring around the lit middle, and a bright outline on the cell edge
    float ring = 1.0 - smoothstep(0.0, aa * 1.5 + 0.012, abs(e - inner));
    float outline = smoothstep(0.5 - aa * 2.0 - 0.018, 0.5, e);
    col = mix(col, edge, max(ring * 0.4, outline));
    a = max(a, max(ring * 0.14, outline * 0.65));

    // Now and then a cell flares up.
    float flare = smoothstep(0.92, 1.0, sin(time * 0.9 + r * 60.0)) * (1.0 - e * 1.6);
    col = mix(col, vec3(0.88, 1.0, 1.0), flare * 0.7);
    a = max(a, flare * 0.45);
    return vec4(col, a);
}

// The look: a light blue glass of hexagons, thin bright outlines, faint breathing centres and the
// odd flare. Close up the cells are 1.6 blocks wide; further out they grow to 4.8 so it stays readable.
vec4 cz_wall_paint(vec3 rel, float radius, float dist, float lift, float time) {
    float h = rel.y;
    if (h < 0.0 || h > CZ_WALL_HEIGHT) return vec4(0.0);
    float angle = atan(rel.z, rel.x) / 6.2831853 + 0.5;

    float aaNear = clamp(dist * 0.0012, 0.01, 0.12);
    vec4 c = cz_hex_layer(angle, radius, h, 1.6, aaNear, time);
    float far = smoothstep(40.0, 100.0, dist);
    if (far > 0.0) {
        vec4 big = cz_hex_layer(angle, radius, h, 4.8, clamp(dist * 0.0005, 0.01, 0.1), time);
        c = mix(c, big, far);
    }

    // A glow where the wall meets the ground or the sea.
    float foot = exp(-h * 0.35);
    c.rgb = mix(c.rgb, vec3(0.6, 0.92, 1.0), foot * 0.5);
    c.a += foot * 0.3;

    // Only shown up to 8 chunks away (fading out over the last chunk), and it fades out a good
    // way above the player.
    c.a *= 1.0 - smoothstep(CZ_WALL_VIEW - 16.0, CZ_WALL_VIEW, dist);
    c.a *= 1.0 - smoothstep(30.0, 90.0, lift);
    c.a *= 1.0 - smoothstep(CZ_WALL_HEIGHT - 40.0, CZ_WALL_HEIGHT, h);
    return vec4(c.rgb, clamp(c.a, 0.0, 0.9));
}

// One pixel of the wall. time is in seconds. depth gets the depth to write (1.0 when nothing hit).
vec4 cz_wall_fragment(vec4 p0, vec4 p2, vec3 farPoint, vec3 origin, float time, out float depth) {
    depth = 1.0;
    if (p0.w <= 0.0 || p2.w <= 0.0) return vec4(0.0);
    vec3 a = p0.xyz / p0.w;
    vec3 b = p2.xyz / p2.w;
    vec3 centre = (a + b) * 0.5;
    float radius = length((b - a).xz) * 0.35355339;   // side = diameter, so the diagonal is 2r * sqrt(2)
    vec3 dir = normalize(farPoint - origin);

    vec2 oc = origin.xz - centre.xz;
    float qa = dot(dir.xz, dir.xz);
    if (qa < 1e-7 || radius < 0.01) return vec4(0.0);
    float qb = dot(oc, dir.xz);
    float qc = dot(oc, oc) - radius * radius;
    float disc = qb * qb - qa * qc;
    if (disc < 0.0) return vec4(0.0);
    float sq = sqrt(disc);
    // Inside the zone you see the wall ahead of you; outside it, the near side.
    float t = qc < 0.0 ? (-qb + sq) / qa : (-qb - sq) / qa;
    if (t <= 0.0) return vec4(0.0);

    vec3 hit = origin + dir * t;
    vec4 c = cz_wall_paint(hit - centre, radius, t, hit.y - origin.y, time);
    if (c.a <= 0.0) return c;
    vec4 clip = ProjMat * ModelViewMat * vec4(hit, 1.0);
    depth = clamp(clip.z / clip.w * 0.5 + 0.5, 0.0, 1.0);
    return c;
}
