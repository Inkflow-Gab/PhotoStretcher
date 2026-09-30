# How the stretch works

Short version: the picture is cut into three horizontal strips, the middle one is scaled
vertically, and the three are stacked back together. The code just does it with exact numbers
and without ever building an intermediate bitmap.

## 1. The rule

Take an image that is `W` x `H`. Two lines sit at fractions of the height:

```
b0 = topLine    * H      (pixels)
b1 = bottomLine * H
```

The band between them is scaled by `f`. Every old row `d` moves to a new row:

```
d <= b0  ->  d                                  part A, untouched
d <= b1  ->  b0 + (d - b0) * f                 part B, stretched by f
d >= b1  ->  d + (b1 - b0) * (f - 1)           part C, slides down
```

So the result is

```
width  = W
height = (H - (b1 - b0)) + (b1 - b0) * f
```

which is the same as `A + B * f + C`. Part C only ever *translates*, it is never scaled, so
the pixels above and below the band come out exactly as they went in. That is the whole trick
and it is why this is not a resize.

`f = 1` leaves the picture alone, `f = 2` makes the band twice as tall, `f = 0.5` half as tall.
Horizontal mode is the same formula with width and height swapped.

## 2. Why fractions, not pixels

Both line positions are stored as fractions of the picture (0.0 - 1.0), never as screen pixels
or as pixels of any particular bitmap. The preview multiplies them by the size of the preview
bitmap, the export multiplies the identical numbers by the size of the full resolution bitmap.
The preview therefore cannot drift away from the exported file, and the same code path handles a
300 pixel preview and a 12000 pixel original.

## 3. More than one stretch

The mapping above is continuous and piecewise linear, so a second stretch applied to the
result of the first one is simply the same mapping applied again to the *destination*
offsets. Every cut gets split wherever it crosses a band edge, and the whole list of stretches
collapses into one flat list of straight cuts:

```kotlin
data class Piece(srcStart, srcEnd, dstStart, dstEnd)   // one straight cut
StretchMath.layout(baseLength, ops, axis) -> StretchLayout(length, pieces)
```

`StretchRenderer` then draws each `Piece` straight from the source bitmap into the result with
one `drawBitmap(src, dst)` call. No intermediate bitmaps, so ten chained 4x stretches cost the
same memory as one, and there is no accumulated resampling error: the unstretched parts are a
1:1 blit whenever the scale is 1.

If the user mixes horizontal and vertical stretches in the same edit, `StretchRenderer` falls
back to applying them one after the other. That is only reachable by flipping the mode chip
mid edit, so the slower path is fine there.

## 4. What the editor draws

`ResultLayout` turns the fractions into screen pixels once, and the drawing code and the touch
handling both use that one object, so they can never disagree:

```
scale = min(1, availableSpace / stretchedLength)
```

A scale of exactly 1 means the picture is drawn at its natural size and the top and bottom
parts are pixel for pixel their original size on screen. Only when the stretched result no
longer fits on screen is the whole thing scaled down to fit, so nothing is ever cut off.

## 5. Quality

- The preview uses `FilterQuality.Medium` when the GPU scales a strip.
- The export uses `Paint(ANTI_ALIAS | FILTER_BITMAP | DITHER)` with `drawBitmap(src, dst)`,
  which is bilinear on Android, and is only actually invoked for strips that really are scaled.
- Strips above and below the band are blitted 1:1, so they are not resampled at all.
- EXIF orientation is applied when the photo is read, so a sideways photo is never stretched
  sideways and the lines always match what you see.

## 6. Smooth edges

At a seam, the strip above and the strip below are drawn a second time over a few pixels with
opposite alpha ramps (a `LinearGradient` shader in the export, a handful of partial alpha
`drawImage` calls in the preview). The result is a linear cross fade of the two textures over
roughly 1/300th of the image, instead of a hard cut. The preview and the export use the same
width, so what you see is what you get.

## 7. Memory

An `ARGB_8888` bitmap costs 4 bytes per pixel, so a 12MP photo is about 48 MB and stretching
half of it by 4x would need about 144 MB. `MemoryPlan` handles this:

1. Work out the final size with `StretchMath.outputSize` (no allocation, just arithmetic).
2. `scaleFor` shrinks it until it fits into a third of the heap, capped at 192 MB and 12000
   pixels on a side.
3. Read the source at a matching size instead of decoding a huge file, and hand the remaining
   scale to the renderer, which produces exactly the planned size.
4. If the device still runs out of memory, the export is retried at half the size, twice.

The editor itself never decodes more than a 1400 pixel copy, so dragging the lines stays smooth
even on a 50MP photo.
