.section .rodata, "a"
.global _binary_xy_embedded_bin_start
.global _binary_xy_embedded_bin_end
.global _binary_xy_embedded_bin_size
.align 4
_binary_xy_embedded_bin_start:
.incbin "xy_embedded.bin"
_binary_xy_embedded_bin_end:
.set _binary_xy_embedded_bin_size, _binary_xy_embedded_bin_end - _binary_xy_embedded_bin_start
