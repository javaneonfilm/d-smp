#version 330

#moj_import <minecraft:light.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>
#moj_import <minecraft:cz_wall.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;

out vec2 texCoord0;

flat out int czWall;
out vec4 czP0;
out vec4 czP2;
out vec3 czFar;
flat out vec3 czOrigin;

void main() {
    czWall = 0;
    czP0 = czP2 = vec4(0.0);
    czFar = czOrigin = vec3(0.0);
    texCoord0 = UV0;
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    lightMapColor = vec4(1.0);
    overlayColor = vec4(0.0);

    if (cz_is_wall(textureLod(Sampler0, UV0, 0.0))) {
        czWall = 1;
        vertexColor = vec4(1.0);
        gl_Position = cz_wall_vertex(Position, gl_VertexID % 4, czP0, czP2, czFar, czOrigin);
        return;
    }

    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);
    lightMapColor = sample_lightmap(Sampler2, UV2);
    overlayColor = texelFetch(Sampler1, UV1, 0);
}
