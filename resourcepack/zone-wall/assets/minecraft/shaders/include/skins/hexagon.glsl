
#ifdef FLAT_TOP_HEXAGON
const vec2 s = vec2(1.7320508, 1);
#else
const vec2 s = vec2(1, 1.7320508);
#endif

float hex(in vec2 p) {    
    p = abs(p);
    
    #ifdef FLAT_TOP_HEXAGON
    return max(dot(p, s*.5), p.y); // Hexagon.
    #else
    return max(dot(p, s*.5), p.x); // Hexagon.
    #endif    
}

vec4 get_hex(vec2 p) {    

    #ifdef FLAT_TOP_HEXAGON
    vec4 hC = floor(vec4(p, p - vec2(1, .5))/s.xyxy) + .5;
    #else
    vec4 hC = floor(vec4(p, p - vec2(.5, 1))/s.xyxy) + .5;
    #endif

    vec4 h = vec4(p - hC.xy*s, p - (hC.zw + .5)*s);

    return dot(h.xy, h.xy) < dot(h.zw, h.zw) 
        ? vec4(h.xy, hC.xy) 
        : vec4(h.zw, hC.zw + .5);
}

float blendLighten(float base, float blend) {
	return max(blend,base);
}

vec3 blendLighten(vec3 base, vec3 blend) {
	return vec3(blendLighten(base.r,blend.r),blendLighten(base.g,blend.g),blendLighten(base.b,blend.b));
}

vec3 blendLighten(vec3 base, vec3 blend, float opacity) {
	return (blendLighten(base, blend) * opacity + base * (1.0 - opacity));
}

vec4 color_from_pos(RayData ray) {
    float depth = ray.depth; float radius = ray.border_radius;
    int border_color = min(int(border_data_x_float + 0.01), 1);

    float rot = atan(ray.pos.x, ray.pos.y) + PI;
    rot /= PI * 2;
    float height = -ray.pos.z / (2*PI*radius);

    rot = abs(fract(rot * SEPARATORS) - 0.5) / SEPARATORS;

    vec4 bg = BGS[border_color];
    vec4 og_accent = ACCENTS[border_color];
    vec4 dark_glow = DARK_GLOWS[border_color];
    vec3 outline = OUTLINES[border_color];

    vec4 hx = get_hex(vec2(rot, height) * HEX_SIZE * radius);
    float eDist = hex(hx.xy);

    const float MID_HEX_SIZE = 0.09;
    const float VARY = 0.035;

    float time_offset = sin(-GameTime * 1000 + hx.w);

    float hex_size = MID_HEX_SIZE + time_offset* VARY;

    vec4 accent = og_accent + dark_glow * smoothstep(0., .4, eDist - .5 + hex_size + 0.03);

    vec4 color = mix(accent, bg, smoothstep(0., .1, eDist - .5 + hex_size));    
    color.rgb = blendLighten(
        color.rgb,
        outline,
        mix(hx.y, 0, smoothstep(0., .03, abs(eDist - .5 + hex_size)))
    );
    color.a += mix(0.3, 0, smoothstep(0., .03, abs(eDist - .5 + hex_size)));

    if (depth < 8 && eDist -0.5 + hex_size < 0) {
        float alpha = smoothstep(0., .03, abs(eDist - .5 + hex_size));
        vec4 hx_small = get_hex(hx.xy * (9.25 + time_offset*0.75));
        float eDist = hex(hx_small.xy);

        float cellID = hx_small.z + hx_small.w* 123 + hx.z * 4 + hx.w * 4 + 234.0;
        float random = fract(sin(cellID) * 43758.5453);

        float highlightTime = fract(random + GameTime * 150);
        float fade = smoothstep(0, 0.8, 1.0 - abs(highlightTime-0.2));

        if (hex(hx_small.zw/s.yx) < 2.02) {
            color = mix(color, mix(accent, mix(accent, bg, fade), smoothstep(0., .03, abs(eDist - .5 + 0.09))), clamp((8-depth)/3, 0, 1) );
        }
    }

    if (depth >= FAR_DISTANCE) {
        vec4 hx = get_hex(vec2(rot, height) * HEX_FAR_SIZE * radius);
        float eDist = hex(hx.xy);

        float time_offset = sin(-GameTime * 1000 + hx.w * HEX_SIZE / HEX_FAR_SIZE);

        float hex_size = MID_HEX_SIZE + time_offset* VARY;

        color = mix(color, mix(bg, og_accent + dark_glow * smoothstep(0., .3, eDist - .5 + hex_size + 0.03), 0.88 + time_offset*0.12), clamp((depth - FAR_DISTANCE) / FAR_FADE_DISTANCE, 0, 1));
    }

    if (ray.inside) {
        color = fade_out_with_defaults(color, ray.depth);
    }
    float fade_t = (ray.pos.z + min(-ray.ro.z + PLAYER_HEIGHT, HEIGHT) - FADEOFF) / FADEOFF + 1;
    color.a *= clamp(fade_t, 0, 1);
    return color;
}
