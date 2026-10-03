// CartZone squad cards, written for cartzone.vip (plugin side: SquadHud.java, HudText.squadCard).
//
// Each card is a boss bar title, which the game centres at the top of the screen. Text whose
// colour carries the marker (red % 8 == 5 and green % 8 == 3) is moved to the top-left corner.
// The colour also carries: blue bits 0-1 = boss bars above the cards (to lift past them),
// blue bit 2 = this is the player head (drawn twice its size), and red bit 3 / green bit 3 = the
// card's row, so the cards can be spaced further apart than boss bars are.
//
// Every card is exactly CZ_CARD_W wide, so the shader knows where the game put its left edge.

const float CZ_CARD_W = 136.0;
const float CZ_MARGIN = 4.0;
const float CZ_TOP = 3.0;
const float CZ_ROW = 19.0;   // boss bars are 19 GUI pixels apart
const float CZ_PITCH = 24.0; // the cards are 24 apart (22 tall + a 2 px gap)
const float CZ_HEAD_Y = 2.0; // head sits 2 px below the title line

vec3 cz_hud(vec3 pos, vec4 color, vec2 uv, mat4 proj) {
    // GUI only (orthographic), and only the top of the screen where boss bars are.
    if (proj[3][3] != 1.0 || pos.y > 160.0) return pos;
    ivec3 c = ivec3(color.rgb * 255.0 + 0.5);
    if ((c.r & 7) != 5 || (c.g & 7) != 3) return pos;
    int above = c.b & 3;
    bool head = (c.b & 4) != 0;
    int row = ((c.r >> 3) & 1) * 2 + ((c.g >> 3) & 1);

    // The boss bar title starts at guiWidth / 2 - width / 2 (integer maths, width rounded up).
    float guiWidth = ceil(2.0 / proj[0][0] - 0.01);
    float left = floor(guiWidth / 2.0) - floor(CZ_CARD_W / 2.0);
    pos.x += CZ_MARGIN - left;
    pos.y += CZ_TOP - float(above + row) * CZ_ROW + float(row) * CZ_PITCH;

    if (head) {
        // The skin's face is u 8-16, v 8-16 of 64 (hat 40-48). Grow right and down to 16x16.
        if ((int(round(uv.x * 8.0)) & 1) == 0) pos.x += 8.0;
        if ((int(round(uv.y * 8.0)) & 1) == 0) pos.y += 8.0;
        pos.y += CZ_HEAD_Y;
    }
    return pos;
}
