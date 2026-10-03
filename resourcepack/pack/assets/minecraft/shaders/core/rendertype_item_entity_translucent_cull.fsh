#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:cz_wall.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec2 texCoord1;

flat in int czWall;
in vec4 czP0;
in vec4 czP2;
in vec3 czFar;
flat in vec3 czOrigin;

out vec4 fragColor;

void main() {
    if (czWall == 1) {
        float depth;
        vec4 wall = cz_wall_fragment(czP0, czP2, czFar, czOrigin, GameTime * 1200.0, depth);
        if (wall.a < 0.01) {
            discard;
        }
        gl_FragDepth = depth;
        fragColor = wall;
        return;
    }
    gl_FragDepth = gl_FragCoord.z;

    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
