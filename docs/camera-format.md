# What the cameras actually deliver

Short version: each camera hands over a **720x480 interlaced** frame, and this app — like
upstream before it — has been recording half of it. The missing scan lines are in the same
buffer we already read.

This document is the evidence, because the driver says otherwise and the conclusion is worth
more than one person's word for it.

## What the driver claims

Asked with `VIDIOC_G_FMT`, every one of the four devices answers the same:

```
/dev/video14: UYVY 720x480 stride=1440 size=691200 field=1 NONE (progressive)
/dev/video15: UYVY 720x480 stride=1440 size=691200 field=1 NONE (progressive)
/dev/video16: UYVY 720x480 stride=1440 size=691200 field=1 NONE (progressive)
/dev/video17: UYVY 720x480 stride=1440 size=691200 field=1 NONE (progressive)
```

The arithmetic is consistent — 720 x 480 x 2 bytes = 691200 — so the buffer really does carry
480 lines. `field=1` is `V4L2_FIELD_NONE`: the driver is declaring a progressive frame.

It is wrong.

## What is actually in it

The capture path keeps rows 0-239 and discards the rest. If the buffer were progressive, those
rows would be the **top half of the scene** — sky and horizon, no road, no bonnet. Watch any clip
this app has ever recorded: the front cell shows sky, horizon, road *and* the car's own bonnet at
the bottom. The whole vertical field of view is in those 240 rows.

So rows 0-239 are not the top half of a frame. They are a **field**: every other scan line, with
the rest of the image in rows 240-479.

## Proving it

Dump one raw buffer and compare the halves. Call the top half `A` (rows 0-239) and the bottom
half `B` (rows 240-479), and work on luma only (the odd bytes of UYVY).

From a night capture of a static scene, mean luma 40 — a deliberately unfavourable case, because
sensor noise is at its worst relative to signal:

| measurement | value |
|---|---|
| halves byte-identical? | no — 73% of bytes differ |
| `mean │B − A│` | 2.58 |
| vertical gradient within one half, `mean │A[k+1] − A[k]│` | 2.27 |
| `mean │B[k] − (A[k]+A[k+1])/2│` | 2.27 |
| horizontal gradient | 1.31 |

Two things follow.

**B's rows sit between A's rows.** `B` is closer to the average of two neighbouring `A` rows
(2.27) than it is to the `A` row of the same index (2.58). A duplicate would be the other way
round. And `│B − A│` is the same size as the vertical gradient rather than far below it, which is
what a half-line spatial offset looks like — not what noise looks like.

**The interleave is exact.** Weave the halves into one 480-row image and measure the vertical
gradient separately at the A→B boundaries and the B→A boundaries:

```
A→B junctions: 2.58
B→A junctions: 2.57      imbalance: 0%
```

This is the one that settles it. In a genuine interlaced pair the fields mesh, and the gradient
is uniform across every boundary. Had one half been a copy of the other, the two figures would
alternate between roughly zero and a full step. They do not differ at all.

<p align="center">
  <img src="https://ws2.tommasovietina.it/mg4/MG4_Dashcam/daylight-comparison.png" alt="The same crop twice: on the left the deinterlaced frame, on the right the same frame with the second field discarded and the lines doubled. The right side shows obvious horizontal banding." width="100%">
</p>

<p align="center">
  <em>One crop of one recorded frame, magnified 5x without smoothing. Left: as 1.1.0-beta.8
  records it. Right: the same frame with the second field thrown away and the lines doubled,
  which is what every release before it wrote to disk. Nothing else differs - same instant, same
  camera, same encoder.</em>
</p>

**The gain is real.** Vertical detail energy, woven against line-doubled:

```
woven:         2.57
line-doubled:  1.13      +127%
```

**A is the top field.** Weaving A-first gives a vertical gradient of 2.575; weaving B-first gives
2.968. The correct interleave is the one that produces the more coherent image, so rows 0-239 are
the top field — which is also what the app already keeps.

## Reproducing it

Record one raw buffer next to the clips — a few lines in the capture loop, writing
`buffer.bytesused` bytes straight to a file — then:

```python
import numpy as np
f = np.fromfile('raw_video14_720x480.uyvy', dtype=np.uint8).reshape(480, 1440)
A, B = f[:240, 1::2].astype(float), f[240:, 1::2].astype(float)

woven = np.empty((480, 720)); woven[0::2], woven[1::2] = A, B
d = np.abs(woven[1:] - woven[:-1])
print('A->B', d[0::2].mean(), ' B->A', d[1::2].mean())   # equal => interlaced
```

Prefer a **static** scene. On anything moving the fields are 1/50 s apart and the difference
between them is motion, not scan lines.

## What the factory app does

`libv4l2utils.so`, inside `com.saicmotor.hmi.aroundview`, exports:

```
v4l2_OpenMtkDI
v4l2_mtkdi_processed
```

MtkDI is the MediaTek hardware **de-interlacer**. The factory pipeline is capture → DI block (a
mem2mem V4L2 device with `input`/`output` queues, buffers from `/dev/ion`) → progressive NV12
720x480 → ArcSoft AVM for the stitching. Its error strings show `VIDIOC_S_FMT` on both queues:
it *requests* a format, where this app only asks what the format happens to be.

So the 720x480 stills `RecordActivity` leaves behind are not proof that the sensor is
progressive. They are the de-interlacer's output. The hardware to reconstruct the full frame has
been there all along.

This is identical in R63 and R71: same 343 exported symbols, same strings, same line numbers —
only the build-server path baked into the error messages differs.

## What this app does about it

Since **1.1.0-beta.9** the capture path weaves the fields back together, motion-adaptively, in
`deinterlaceLocked`.

The even lines are the top field and are taken as they are: they were captured, they are real.
Every odd line has two candidates — the bottom-field line captured at that position, and the
average of the top-field lines above and below it. Which is right depends on whether anything
moved in the 1/50 s between the two fields, so the choice is made per pixel.

**Motion is found by comparing this frame's top field with the previous one.** Same lines, same
parity: a difference can only be movement or noise, never vertical detail. Where a pixel moved,
the interpolation is used; where it did not, the captured line is, and the vertical resolution
is real.

The interpolation is exactly the picture this app produced before, so the worst case of the
whole change is the old behaviour, on the pixels that would have combed.

### The detector that did not work

The first attempt used no history. It asked whether a bottom-field pixel sat outside *both* of
its top-field neighbours in the same direction — the shape of a combed edge. It is appealing
because it needs nothing but the frame in hand, and on the car it failed in both directions at
once:

| region | interpolated | wanted |
|---|---|---|
| a still lawn | 52% | almost none — that is the detail this change exists to recover |
| a walking leg | 27% | almost all — the combing was plainly visible |

The reason is that a single frame cannot tell a moving edge from a sharp one. Worse, at the edge
of a moving object — exactly where combing shows — the two neighbours fall on opposite sides of
the edge, the two differences take opposite signs, and the test cancels itself out. It was
blindest precisely where it was needed.

### What to expect from it

The gain is largest when the scene is still and shrinks as it moves, because a moving scene
genuinely has no 480 lines belonging to one instant. Stopped at a light, parked, manoeuvring:
full vertical resolution. Driving: most of the frame interpolated, which is what earlier
releases recorded everywhere.

That is not an artefact of this implementation. Every motion-adaptive deinterlacer does it,
including the hardware one — it interpolates moving regions too, just better.

## What would be better

The MTK de-interlacer — what the factory app does. Motion-adaptive in hardware, at no CPU cost,
and better than a comb detector working from one frame. It means implementing the full V4L2
mem2mem path with ION buffers, which is why it is not what happened first.

Either way, the vertical resolution was never lost. It was being thrown away.
