#version 330

// 26.x port of the lodestone worldborder fragment stage. Vanilla 26.x item.fsh with the
// square-border and ray-marched circle-border branches from the old
// rendertype_item_entity_translucent_cull spliced in; see item.vsh for the detection.
// GameTime now lives in the Globals UBO instead of a loose uniform; the item bind group
// includes Globals, Projection, DynamicTransforms, Fog and Lighting, so raymarch.glsl's
// ProjMat / fog / GameTime references all resolve here.

#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:types.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;

flat in int type;

in vec4 ray_pos1, ray_pos2, ray_pos3, ray_pos4;
in vec4 abs_pos1, abs_pos2, abs_pos3, abs_pos4;
in vec3 ray_uv1, ray_uv2;
in vec3 ray_glPos;
in vec4 ray_lightMapColor;

in vec2 lodestone_xy;
in vec3 lodestone_pos;
in vec3 lodestone_topLeft;
in vec4 lodestone_position2;
in vec4 lodestone_position0;
in vec4 lodestone_position13;
in vec4 lodestone_color;

#define MAX_DEPTH 1000000

out vec4 fragColor;

#moj_import <minecraft:raymarch.glsl>

vec2 mapToRangeMod(vec2 t, vec2 from, vec2 to, vec2 mappedFrom, vec2 mappedTo) {
    vec2 range = to - from;

    vec2 normalized = fract((t - from) / range);

    return mix(mappedFrom, mappedTo, normalized);
}

void main() {
    gl_FragDepth = gl_FragCoord.z;

    if (type == TYPE_SQUARE_BORDER || type == TYPE_SQUARE_BORDER_INNER) {

        int primitive = ((gl_PrimitiveID) + int(type == TYPE_SQUARE_BORDER_INNER)) % 2;
        vec2 topLeft = lodestone_topLeft.xy / lodestone_topLeft.z;
        vec3 pos0 = lodestone_position0.xyz / lodestone_position0.w;
        vec3 pos2 = lodestone_position2.xyz / lodestone_position2.w;
        vec3 pos13 = lodestone_position13.xyz / lodestone_position13.w;

        float height = length((primitive == 0 ? pos0 : pos2)-pos13);
        float width = length((primitive == 1 ? pos0 : pos2)-pos13);

        ivec2 size = textureSize(Sampler0, 0);
        vec2 pixel = 1 / vec2(size);

        topLeft.y += pixel.y;
        vec2 coord = topLeft + fract(mapToRangeMod(lodestone_xy, vec2(0), 1 / vec2(width, height), vec2(0), vec2(1)) + vec2(GameTime*128) * vec2(-1, 1)) * 15.965 / vec2(size);
        fragColor = texture(Sampler0, coord) * lodestone_color;
        if (type == TYPE_SQUARE_BORDER_INNER) fragColor = fade_out(fragColor, length(lodestone_pos), 16.0, 64.0);

        if (fragColor.a < 0.1) {
            discard;
        }
        return;
    } else if (type == TYPE_BORDER) {
        raymarch(false);
        return;
    } else if (type == TYPE_BORDER_INNER) {
        raymarch(true);
        return;
    }

    // ---- vanilla 26.x item path ----
    vec4 color = texture(Sampler0, texCoord0);
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif

    color *= vertexColor * ColorModulator;
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    color *= lightMapColor;

    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
