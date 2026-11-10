import struct, zlib, os
os.makedirs('photos', exist_ok=True)
def png(path, w, h, color):
    rows = b''
    for y in range(h):
        row = bytearray([0])
        for x in range(w):
            stripe = ((x // 100 + y // 100) % 2)
            r, g, b = color
            if stripe: r, g, b = 255 - r, 255 - g, 255 - b
            row += bytes((r, g, b))
        rows += bytes(row)
    def chunk(t, d):
        c = struct.pack('>I', len(d)) + t + d
        return c + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    open(path, 'wb').write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b''))
for i in range(1, 13):
    png(f'photos/img_{i}.png', 600, 400, (i * 40 % 256, 255 - i * 30, 120))
