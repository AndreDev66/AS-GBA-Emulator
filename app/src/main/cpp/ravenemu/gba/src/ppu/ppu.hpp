#pragma once

#include "memory/bus.hpp"
#include "dma/dma_controller.hpp"

namespace ravenemu::gba {

class Ppu {
public:
    static constexpr int screen_width = 240;
    static constexpr int screen_height = 160;
    static constexpr int state_field_count = 9;
    explicit Ppu(Bus& bus) : bus_(bus) {}

    void tick(int cycles) {
        const auto next_event = in_hblank ? line_cycles_total : hdraw_cycles;
        if (line_cycles_ + cycles < next_event) { line_cycles_ += cycles; return; }
        auto remaining = cycles;
        while (remaining > 0) {
            const auto step = std::min(remaining, line_cycles_total - line_cycles_);
            line_cycles_ += step; remaining -= step;
            if (!in_hblank && line_cycles_ >= hdraw_cycles) {
                in_hblank = true;
                if (vcount < screen_height) {
                    if (render_enabled) {
                        if (bus_.diagnostics.measuring_time) {
                            const auto started = std::chrono::steady_clock::now(); render_scanline(vcount);
                            bus_.diagnostics.ppu_nanos += std::chrono::duration_cast<std::chrono::nanoseconds>(
                                std::chrono::steady_clock::now() - started).count();
                        } else render_scanline(vcount);
                    }
                    bg2_ref_x_ = add32(bg2_ref_x_, signed16(reg16(0x22)));
                    bg2_ref_y_ = add32(bg2_ref_y_, signed16(reg16(0x26)));
                    bg3_ref_x_ = add32(bg3_ref_x_, signed16(reg16(0x32)));
                    bg3_ref_y_ = add32(bg3_ref_y_, signed16(reg16(0x36)));
                    if (irq_enabled(0x10) && interrupts) interrupts->request(InterruptController::hblank);
                    if (dma) dma->trigger_hblank();
                }
            }
            if (line_cycles_ >= line_cycles_total) {
                line_cycles_ = 0; in_hblank = false; vcount = (vcount + 1) % total_lines;
                in_vblank = vcount >= screen_height;
                if (vcount == 0) {
                    reload_affine_references();
                    if (collect_layer_stats) { layer_pixels = pending_layer_pixels_; pending_layer_pixels_.fill(0); }
                    if (collect_frame_stats) measure_luma();
                }
                if (vcount == screen_height) {
                    if (irq_enabled(0x08) && interrupts) interrupts->request(InterruptController::vblank);
                    if (dma) dma->trigger_vblank();
                }
                vcount_match = vcount == bus_.io[5];
                if (vcount_match && irq_enabled(0x20) && interrupts) interrupts->request(InterruptController::vcount);
            }
        }
    }
    [[nodiscard]] int dispstat_low() const noexcept {
        auto status = (in_vblank ? 1 : 0) | (in_hblank ? 2 : 0) | (vcount_match ? 4 : 0);
        return (bus_.io[4] & 0xf8) | status;
    }
    void on_affine_reference_write(int offset) noexcept {
        if (offset < 0x2c) bg2_ref_x_ = signed28(reg32(0x28));
        else if (offset < 0x30) bg2_ref_y_ = signed28(reg32(0x2c));
        else if (offset < 0x3c) bg3_ref_x_ = signed28(reg32(0x38));
        else bg3_ref_y_ = signed28(reg32(0x3c));
    }
    [[nodiscard]] std::array<std::int32_t, state_field_count> state_fields() const noexcept {
        return {vcount, line_cycles_, in_vblank ? 1 : 0, in_hblank ? 1 : 0,
                vcount_match ? 1 : 0, bg2_ref_x_, bg2_ref_y_, bg3_ref_x_, bg3_ref_y_};
    }
    /** Cycles restants avant le retour à VCOUNT 0, position de ligne 0. */
    [[nodiscard]] int cycles_until_next_frame() const noexcept {
        return total_lines * line_cycles_total - (vcount * line_cycles_total + line_cycles_);
    }
    void restore_state(std::span<const std::int32_t> values) {
        if (values.size() != state_field_count || values[0] < 0 || values[0] >= total_lines ||
            values[1] < 0 || values[1] >= line_cycles_total) throw SaveStateError("État PPU GBA invalide");
        vcount = values[0]; line_cycles_ = values[1]; in_vblank = values[2] != 0;
        in_hblank = values[3] != 0; vcount_match = values[4] != 0;
        bg2_ref_x_ = values[5]; bg2_ref_y_ = values[6]; bg3_ref_x_ = values[7]; bg3_ref_y_ = values[8];
        latch_mosaic_references();
    }

    std::array<std::int32_t, screen_width * screen_height> frame{};
    std::array<std::int32_t, 5> layer_pixels{};
    bool render_enabled{true};
    bool collect_layer_stats{};
    bool collect_frame_stats{};
    int frame_luma_min{};
    int frame_luma_max{};
    int frame_luma_mean{};
    int vcount{};
    bool in_vblank{};
    bool in_hblank{};
    bool vcount_match{};
    InterruptController* interrupts{};
    DmaController* dma{};

private:
    static constexpr int layer_backdrop = 4;
    static constexpr int layer_obj = 4;
    static constexpr int layer_backdrop_id = 5;
    static constexpr int obj_bit = 1 << 4;
    static constexpr int window_effects = 1 << 5;
    static constexpr int window_all = 0x3f;
    static constexpr int object_tile_base = 0x10000;
    static constexpr int object_palette_base = 256;
    static constexpr int hdraw_cycles = 960;
    static constexpr int line_cycles_total = 1232;
    static constexpr int total_lines = 228;
    static constexpr std::array object_width_{8,16,32,64, 16,32,32,64, 8,8,16,32};
    static constexpr std::array object_height_{8,16,32,64, 8,8,16,32, 16,32,32,64};

    [[nodiscard]] bool irq_enabled(int mask) const noexcept { return (bus_.io[4] & mask) != 0; }
    [[nodiscard]] int reg16(int offset) const noexcept {
        return bus_.io[static_cast<std::size_t>(offset)] | bus_.io[static_cast<std::size_t>(offset + 1)] << 8;
    }
    [[nodiscard]] std::int32_t reg32(int offset) const noexcept {
        return i32(static_cast<std::uint32_t>(reg16(offset)) |
                   (static_cast<std::uint32_t>(reg16(offset + 2)) << 16U));
    }
    [[nodiscard]] static std::int32_t signed16(int value) noexcept {
        return static_cast<std::int16_t>(static_cast<std::uint16_t>(value));
    }
    [[nodiscard]] static std::int32_t signed28(std::int32_t value) noexcept {
        return sign_extend(u32(value) & 0x0fff'ffffU, 28);
    }
    [[nodiscard]] int vram8(int offset) const noexcept {
        return offset >= 0 && offset < static_cast<int>(bus_.vram.size()) ? bus_.vram[static_cast<std::size_t>(offset)] : 0;
    }
    [[nodiscard]] int vram16(int offset) const noexcept { return vram8(offset) | vram8(offset + 1) << 8; }
    [[nodiscard]] int oam16(int offset) const noexcept {
        return bus_.oam[static_cast<std::size_t>(offset)] | bus_.oam[static_cast<std::size_t>(offset + 1)] << 8;
    }
    [[nodiscard]] static std::int32_t bgr555(int color) noexcept {
        const auto r = color & 31; const auto g = (color >> 5) & 31; const auto b = (color >> 10) & 31;
        return i32(0xff000000U | static_cast<std::uint32_t>((r << 3) | (r >> 2)) << 16U |
            static_cast<std::uint32_t>((g << 3) | (g >> 2)) << 8U | static_cast<std::uint32_t>((b << 3) | (b >> 2)));
    }
    [[nodiscard]] std::int32_t palette_color(int index) const noexcept {
        const auto offset = static_cast<std::size_t>(index * 2);
        if (offset + 1 >= bus_.palette.size()) return i32(0xff000000U);
        return bgr555(bus_.palette[offset] | bus_.palette[offset + 1] << 8);
    }
    static int positive_mod(int value, int modulus) noexcept {
        const auto result = value % modulus; return result < 0 ? result + modulus : result;
    }
    void reload_affine_references() noexcept {
        bg2_ref_x_ = signed28(reg32(0x28)); bg2_ref_y_ = signed28(reg32(0x2c));
        bg3_ref_x_ = signed28(reg32(0x38)); bg3_ref_y_ = signed28(reg32(0x3c));
        latch_mosaic_references();
    }
    // MOSAIC (0x4000004c) : quatre quartets, chacun « taille du bloc moins un ».
    [[nodiscard]] int bg_mosaic_width() const noexcept { return (reg16(0x4c) & 15) + 1; }
    [[nodiscard]] int bg_mosaic_height() const noexcept { return ((reg16(0x4c) >> 4) & 15) + 1; }
    [[nodiscard]] int obj_mosaic_width() const noexcept { return ((reg16(0x4c) >> 8) & 15) + 1; }
    [[nodiscard]] int obj_mosaic_height() const noexcept { return ((reg16(0x4c) >> 12) & 15) + 1; }
    // La mosaïque verticale fait afficher à toute une bande de lignes ce que
    // montre sa première ligne. Pour un plan affine, cela revient à figer le
    // point de référence interne au début de la bande : lui continue d'avancer
    // ligne par ligne, comme sur le matériel, mais le rendu utilise la copie.
    void latch_mosaic_references() noexcept {
        bg2_mosaic_ref_x_ = bg2_ref_x_; bg2_mosaic_ref_y_ = bg2_ref_y_;
        bg3_mosaic_ref_x_ = bg3_ref_x_; bg3_mosaic_ref_y_ = bg3_ref_y_;
    }
    void render_scanline(int y) {
        if (y % bg_mosaic_height() == 0) latch_mosaic_references();
        const auto display = reg16(0);
        const auto row = static_cast<std::size_t>(y * screen_width);
        if ((display & 0x80) != 0) { std::fill_n(frame.begin() + static_cast<std::ptrdiff_t>(row), screen_width, i32(0xffffffffU)); return; }
        const auto blend = reg16(0x50); simple_composition_ = (blend & 0x3fc0) == 0;
        const auto backdrop = palette_color(0);
        for (int x = 0; x < screen_width; ++x) {
            const auto index = static_cast<std::size_t>(x);
            line_color_[index] = backdrop; line_priority_[index] = layer_backdrop; line_layer_[index] = layer_backdrop_id;
            line_color2_[index] = backdrop; line_priority2_[index] = layer_backdrop; line_layer2_[index] = layer_backdrop_id;
            obj_opaque_[index] = false; obj_semitransparent_[index] = false; obj_window_[index] = false;
        }
        render_sprites(y, display); compute_window_mask(y, display);
        switch (display & 7) {
        case 0: case 1: case 2: render_backgrounds(y, display); break;
        case 3: render_mode3(y, display); break; case 4: render_mode4(y, display); break;
        case 5: render_mode5(y, display); break; default: break;
        }
        if (simple_composition_) compose_simple(row); else compose(row, blend);
    }
    void push_pixel(int x, std::int32_t color, int priority, int layer) noexcept {
        const auto index = static_cast<std::size_t>(x);
        if (collect_layer_stats) ++pending_layer_pixels_[static_cast<std::size_t>(layer)];
        line_color2_[index] = line_color_[index]; line_priority2_[index] = line_priority_[index]; line_layer2_[index] = line_layer_[index];
        line_color_[index] = color; line_priority_[index] = priority; line_layer_[index] = layer;
    }
    void render_backgrounds(int y, int display) {
        const auto mode = display & 7; const auto first = mode == 2 ? 2 : 0; const auto last = mode == 1 ? 2 : 3;
        std::array<std::pair<int,int>, 4> order{}; int count{};
        for (int bg = first; bg <= last; ++bg) if ((display & (1 << (8 + bg))) != 0) {
            order[static_cast<std::size_t>(count++)] = {(reg16(0x08 + bg * 2) & 3) * 4 + bg, bg};
        }
        for (int index = 1; index < count; ++index) {
            const auto value = order[static_cast<std::size_t>(index)];
            auto position = index;
            while (position > 0 && order[static_cast<std::size_t>(position - 1)].first < value.first) {
                order[static_cast<std::size_t>(position)] = order[static_cast<std::size_t>(position - 1)];
                --position;
            }
            order[static_cast<std::size_t>(position)] = value;
        }
        for (int index = 0; index < count; ++index) {
            const auto bg = order[static_cast<std::size_t>(index)].second;
            if ((mode == 1 && bg == 2) || (mode == 2 && bg >= 2)) draw_affine(bg);
            else draw_text(bg, y);
        }
    }
    void draw_affine(int bg) {
        const auto control = reg16(0x08 + bg * 2); const auto priority = control & 3;
        const auto char_base = ((control >> 2) & 3) * 0x4000; const auto screen_base = ((control >> 8) & 31) * 0x800;
        const auto wraps = (control & 0x2000) != 0; const auto tiles = 16 << ((control >> 14) & 3);
        const auto pixels = tiles * 8; const auto param = bg == 2 ? 0x20 : 0x30;
        const auto pa = signed16(reg16(param)); const auto pc = signed16(reg16(param + 4));
        const auto mosaic = (control & 0x40) != 0; const auto mosaic_width = mosaic ? bg_mosaic_width() : 1;
        auto current_x = mosaic ? (bg == 2 ? bg2_mosaic_ref_x_ : bg3_mosaic_ref_x_) : (bg == 2 ? bg2_ref_x_ : bg3_ref_x_);
        auto current_y = mosaic ? (bg == 2 ? bg2_mosaic_ref_y_ : bg3_mosaic_ref_y_) : (bg == 2 ? bg2_ref_y_ : bg3_ref_y_);
        std::int32_t block_x{}; std::int32_t block_y{};
        for (int x = 0; x < screen_width; ++x) {
            auto texture_x = current_x >> 8; auto texture_y = current_y >> 8;
            current_x = add32(current_x, pa); current_y = add32(current_y, pc);
            // Le parcours avance à chaque point, mais un bloc entier réutilise
            // les coordonnées échantillonnées à sa colonne de gauche.
            if (mosaic) {
                if (x % mosaic_width == 0) { block_x = texture_x; block_y = texture_y; }
                texture_x = block_x; texture_y = block_y;
            }
            if ((window_mask_[static_cast<std::size_t>(x)] & (1 << bg)) == 0) continue;
            if (wraps) { texture_x = positive_mod(texture_x, pixels); texture_y = positive_mod(texture_y, pixels); }
            else if (texture_x < 0 || texture_x >= pixels || texture_y < 0 || texture_y >= pixels) continue;
            const auto tile = vram8(screen_base + texture_y / 8 * tiles + texture_x / 8);
            const auto color = vram8(char_base + tile * 64 + (texture_y & 7) * 8 + (texture_x & 7));
            if (color != 0) push_pixel(x, palette_color(color), priority, bg);
        }
    }
    void draw_text(int bg, int y) {
        const auto control = reg16(0x08 + bg * 2); const auto priority = control & 3;
        const auto char_base = ((control >> 2) & 3) * 0x4000; const auto screen_base = ((control >> 8) & 31) * 0x800;
        const auto eight_bpp = (control & 0x80) != 0; const auto size = (control >> 14) & 3;
        const auto width = size == 1 || size == 3 ? 512 : 256; const auto height = size >= 2 ? 512 : 256;
        // BGxCNT bit 6 : mosaïque du plan. Le repère d'écran est ramené au coin
        // haut-gauche de son bloc *avant* l'ajout du défilement, si bien que la
        // grille des blocs reste fixe à l'écran quand le plan défile.
        const auto mosaic = (control & 0x40) != 0; const auto mosaic_width = mosaic ? bg_mosaic_width() : 1;
        const auto source_y = mosaic ? y - y % bg_mosaic_height() : y;
        const auto effective_y = (source_y + (reg16(0x12 + bg * 4) & 0x1ff)) & (height - 1);
        const auto tile_y = effective_y >> 3; const auto initial_py = effective_y & 7;
        const auto horizontal = reg16(0x10 + bg * 4) & 0x1ff;
        for (int x = 0; x < screen_width; ++x) {
            if ((window_mask_[static_cast<std::size_t>(x)] & (1 << bg)) == 0) continue;
            const auto source_x = mosaic ? x - x % mosaic_width : x;
            const auto effective_x = (source_x + horizontal) & (width - 1); const auto tile_x = effective_x >> 3;
            const auto block_x = tile_x >= 32 ? 1 : 0; const auto block_y = tile_y >= 32 ? 1 : 0;
            const auto block = size == 1 ? block_x : size == 2 ? block_y : size == 3 ? block_y * 2 + block_x : 0;
            const auto entry = vram16(screen_base + block * 0x800 + ((tile_y & 31) * 32 + (tile_x & 31)) * 2);
            const auto tile = entry & 0x3ff; const auto px = (entry & 0x400) != 0 ? 7 - (effective_x & 7) : effective_x & 7;
            const auto py = (entry & 0x800) != 0 ? 7 - initial_py : initial_py;
            int color{};
            if (eight_bpp) color = vram8(char_base + tile * 64 + py * 8 + px);
            else { const auto byte = vram8(char_base + tile * 32 + py * 4 + px / 2); const auto nibble = (px & 1) == 0 ? byte & 15 : byte >> 4; if (nibble != 0) color = ((entry >> 12) & 15) * 16 + nibble; }
            if (color != 0) push_pixel(x, palette_color(color), priority, bg);
        }
    }
    void render_sprites(int y, int display) {
        if ((display & 0x1000) == 0) return;
        const auto one_dimensional = (display & 0x40) != 0;
        for (int sprite = 0; sprite < 128; ++sprite) {
            const auto base = sprite * 8; const auto attr0 = oam16(base); const auto transform = (attr0 >> 8) & 3;
            if (transform == 2 || ((attr0 >> 14) & 3) == 3 || ((attr0 >> 10) & 3) == 3) continue;
            const auto affine = transform == 1 || transform == 3; const auto doubled = transform == 3;
            const auto attr1 = oam16(base + 2); const auto attr2 = oam16(base + 4);
            const auto shape = (attr0 >> 14) & 3; const auto size_key = shape * 4 + ((attr1 >> 14) & 3);
            const auto width = object_width_[static_cast<std::size_t>(size_key)]; const auto height = object_height_[static_cast<std::size_t>(size_key)];
            const auto box_width = doubled ? width * 2 : width; const auto box_height = doubled ? height * 2 : height;
            const auto sprite_y = (y - (attr0 & 0xff)) & 0xff; if (sprite_y >= box_height) continue;
            auto sprite_x = attr1 & 0x1ff; if (sprite_x >= 0x100) sprite_x -= 0x200;
            const auto eight_bpp = (attr0 & 0x2000) != 0; const auto palette_bank = (attr2 >> 12) & 15;
            const auto priority = (attr2 >> 10) & 3; const auto tile_base = attr2 & 0x3ff;
            const auto slots = eight_bpp ? 2 : 1; const auto row_stride = one_dimensional ? width / 8 * slots : 32;
            auto pa = 0x100; auto pb = 0; auto pc = 0; auto pd = 0x100;
            if (affine) { const auto group = ((attr1 >> 9) & 31) * 32; pa = signed16(oam16(group + 6)); pb = signed16(oam16(group + 14)); pc = signed16(oam16(group + 22)); pd = signed16(oam16(group + 30)); }
            const auto horizontal_flip = !affine && (attr1 & 0x1000) != 0; const auto vertical_flip = !affine && (attr1 & 0x2000) != 0;
            // Attribut 0 bit 12 : mosaïque de l'objet. Simplification assumée :
            // les blocs sont comptés dans le repère de l'objet, donc ancrés sur
            // son coin haut-gauche, alors que le matériel compte à partir du
            // bord de l'écran. La différence ne se voit que sur un objet dont la
            // position n'est pas un multiple de la taille du bloc.
            const auto mosaic = (attr0 & 0x1000) != 0; const auto mosaic_width = mosaic ? obj_mosaic_width() : 1;
            const auto sample_y = mosaic ? sprite_y - sprite_y % obj_mosaic_height() : sprite_y;
            for (int column = 0; column < box_width; ++column) {
                const auto screen_x = sprite_x + column; if (screen_x < 0 || screen_x >= screen_width) continue;
                const auto sample_column = mosaic ? column - column % mosaic_width : column;
                int texture_x{}; int texture_y{};
                if (affine) {
                    const auto dx = sample_column - box_width / 2; const auto dy = sample_y - box_height / 2;
                    texture_x = (pa * dx + pb * dy) >> 8; texture_x += width / 2;
                    texture_y = (pc * dx + pd * dy) >> 8; texture_y += height / 2;
                    if (texture_x < 0 || texture_x >= width || texture_y < 0 || texture_y >= height) continue;
                } else { texture_x = horizontal_flip ? width - 1 - sample_column : sample_column; texture_y = vertical_flip ? height - 1 - sample_y : sample_y; }
                const auto tile = tile_base + texture_y / 8 * row_stride + texture_x / 8 * slots;
                int color{}; const auto in_x = texture_x & 7; const auto in_y = texture_y & 7;
                if (eight_bpp) color = vram8(object_tile_base + tile * 32 + in_y * 8 + in_x);
                else { const auto byte = vram8(object_tile_base + tile * 32 + in_y * 4 + in_x / 2); const auto nibble = (in_x & 1) == 0 ? byte & 15 : byte >> 4; if (nibble != 0) color = palette_bank * 16 + nibble; }
                if (color == 0) continue;
                const auto index = static_cast<std::size_t>(screen_x);
                if (((attr0 >> 10) & 3) == 2) { obj_window_[index] = true; continue; }
                if (obj_opaque_[index]) continue;
                if (collect_layer_stats) ++pending_layer_pixels_[layer_obj];
                obj_color_[index] = palette_color(object_palette_base + color); obj_priority_[index] = priority;
                obj_opaque_[index] = true; obj_semitransparent_[index] = ((attr0 >> 10) & 3) == 1;
            }
        }
    }
    void compute_window_mask(int y, int display) {
        const auto win0 = (display & 0x2000) != 0; const auto win1 = (display & 0x4000) != 0; const auto obj = (display & 0x8000) != 0;
        if (!win0 && !win1 && !obj) { window_mask_.fill(window_all); return; }
        const auto inside = reg16(0x48); const auto outside = reg16(0x4a);
        for (int x = 0; x < screen_width; ++x) {
            const auto index = static_cast<std::size_t>(x);
            if (win0 && inside_window(0, x, y)) window_mask_[index] = inside & 0x3f;
            else if (win1 && inside_window(1, x, y)) window_mask_[index] = (inside >> 8) & 0x3f;
            else if (obj && obj_window_[index]) window_mask_[index] = (outside >> 8) & 0x3f;
            else window_mask_[index] = outside & 0x3f;
        }
    }
    bool inside_window(int window, int x, int y) const noexcept {
        const auto horizontal = reg16(0x40 + window * 2); const auto vertical = reg16(0x44 + window * 2);
        const auto left = (horizontal >> 8) & 0xff; const auto right = horizontal & 0xff;
        const auto top = (vertical >> 8) & 0xff; const auto bottom = vertical & 0xff;
        const auto in_x = left <= right ? x >= left && x < right : x >= left || x < right;
        const auto in_y = top <= bottom ? y >= top && y < bottom : y >= top || y < bottom;
        return in_x && in_y;
    }
    void compose_simple(std::size_t row) noexcept {
        for (int x = 0; x < screen_width; ++x) { const auto index = static_cast<std::size_t>(x);
            const auto show = obj_opaque_[index] && (window_mask_[index] & obj_bit) != 0 && obj_priority_[index] <= line_priority_[index];
            frame[row + index] = show ? obj_color_[index] : line_color_[index]; }
    }
    void compose(std::size_t row, int blend) {
        const auto mode = (blend >> 6) & 3;
        for (int x = 0; x < screen_width; ++x) { const auto index = static_cast<std::size_t>(x);
            auto top = line_color_[index]; auto top_layer = line_layer_[index]; auto bottom = line_color2_[index]; auto bottom_layer = line_layer2_[index]; auto semi = false;
            if (obj_opaque_[index] && (window_mask_[index] & obj_bit) != 0) {
                if (obj_priority_[index] <= line_priority_[index]) { bottom = top; bottom_layer = top_layer; top = obj_color_[index]; top_layer = layer_obj; semi = obj_semitransparent_[index]; }
                else if (obj_priority_[index] <= line_priority2_[index]) { bottom = obj_color_[index]; bottom_layer = layer_obj; }
            }
            frame[row + index] = color_effect(index, mode, blend, top, top_layer, bottom, bottom_layer, semi);
        }
    }
    std::int32_t color_effect(std::size_t x, int mode, int blend, std::int32_t top, int top_layer,
                              std::int32_t bottom, int bottom_layer, bool semi) const noexcept {
        if ((window_mask_[x] & window_effects) == 0) return top;
        const auto target1 = (blend & (1 << top_layer)) != 0; const auto target2 = (blend & (1 << (8 + bottom_layer))) != 0;
        if (semi && target2) return alpha(top, bottom);
        if (!target1) return top;
        if (mode == 1 && target2) return alpha(top, bottom);
        if (mode == 2) return brightness(top, true);
        if (mode == 3) return brightness(top, false);
        return top;
    }
    std::int32_t alpha(std::int32_t top, std::int32_t bottom) const noexcept {
        const auto coefficients = reg16(0x52); const auto a = std::min(coefficients & 31, 16); const auto b = std::min((coefficients >> 8) & 31, 16);
        const auto channel = [&](int shift) {
            const auto mixed = ((((u32(top) >> shift) & 0xffU) * static_cast<unsigned>(a)) +
                (((u32(bottom) >> shift) & 0xffU) * static_cast<unsigned>(b))) >> 4U;
            return std::min(255U, mixed);
        };
        return i32(0xff000000U | channel(16) << 16U | channel(8) << 8U | channel(0));
    }
    std::int32_t brightness(std::int32_t color, bool white) const noexcept {
        const auto amount = std::min(reg16(0x54) & 31, 16); std::uint32_t result = 0xff000000U;
        for (const auto shift : {16U, 8U, 0U}) { const auto value = static_cast<int>((u32(color) >> shift) & 0xffU); const auto adjusted = white ? value + ((255 - value) * amount >> 4) : value - (value * amount >> 4); result |= static_cast<std::uint32_t>(adjusted) << shift; }
        return i32(result);
    }
    void render_mode3(int y, int display) {
        if ((display & 0x0400) == 0) return;
        const auto priority = reg16(0x0c) & 3;
        for (int x = 0; x < screen_width; ++x) if ((window_mask_[static_cast<std::size_t>(x)] & 4) != 0) push_pixel(x, bgr555(vram16((y * screen_width + x) * 2)), priority, 2);
    }
    void render_mode4(int y, int display) {
        if ((display & 0x0400) == 0) return;
        const auto page = (display & 0x10) != 0 ? 0xa000 : 0;
        const auto priority = reg16(0x0c) & 3;
        for (int x = 0; x < screen_width; ++x) if ((window_mask_[static_cast<std::size_t>(x)] & 4) != 0) push_pixel(x, palette_color(vram8(page + y * screen_width + x)), priority, 2);
    }
    void render_mode5(int y, int display) {
        if ((display & 0x0400) == 0 || y >= 128) return;
        const auto page = (display & 0x10) != 0 ? 0xa000 : 0;
        const auto priority = reg16(0x0c) & 3;
        for (int x = 0; x < 160; ++x) if ((window_mask_[static_cast<std::size_t>(x)] & 4) != 0) push_pixel(x, bgr555(vram16(page + (y * 160 + x) * 2)), priority, 2);
    }
    void measure_luma() noexcept {
        auto minimum = 255; auto maximum = 0; std::int64_t sum{};
        for (const auto pixel : frame) { const auto raw = u32(pixel); const auto luma = static_cast<int>((54U * ((raw >> 16U) & 255U) + 183U * ((raw >> 8U) & 255U) + 19U * (raw & 255U)) >> 8U); minimum = std::min(minimum, luma); maximum = std::max(maximum, luma); sum += luma; }
        frame_luma_min = minimum;
        frame_luma_max = maximum;
        frame_luma_mean = static_cast<int>(sum / static_cast<std::int64_t>(frame.size()));
    }

    Bus& bus_;
    int line_cycles_{};
    std::int32_t bg2_ref_x_{}; std::int32_t bg2_ref_y_{}; std::int32_t bg3_ref_x_{}; std::int32_t bg3_ref_y_{};
    std::int32_t bg2_mosaic_ref_x_{}; std::int32_t bg2_mosaic_ref_y_{};
    std::int32_t bg3_mosaic_ref_x_{}; std::int32_t bg3_mosaic_ref_y_{};
    bool simple_composition_{};
    std::array<std::int32_t, screen_width> line_color_{}; std::array<int, screen_width> line_priority_{}; std::array<int, screen_width> line_layer_{};
    std::array<std::int32_t, screen_width> line_color2_{}; std::array<int, screen_width> line_priority2_{}; std::array<int, screen_width> line_layer2_{};
    std::array<std::int32_t, screen_width> obj_color_{}; std::array<int, screen_width> obj_priority_{};
    std::array<bool, screen_width> obj_opaque_{}; std::array<bool, screen_width> obj_semitransparent_{}; std::array<bool, screen_width> obj_window_{};
    std::array<int, screen_width> window_mask_{}; std::array<std::int32_t, 5> pending_layer_pixels_{};
};

inline int ppu_dispstat_low(const Ppu* ppu) noexcept { return ppu ? ppu->dispstat_low() : 0; }
inline int ppu_vcount(const Ppu* ppu) noexcept { return ppu ? ppu->vcount : 0; }
inline void ppu_affine_reference_write(Ppu* ppu, int offset) noexcept { if (ppu) ppu->on_affine_reference_write(offset); }

} // namespace ravenemu::gba
