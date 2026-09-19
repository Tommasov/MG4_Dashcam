# Reading four cameras at once

The factory 360 app records all four cameras at 25 fps. This app managed six, and no amount of
making the compositor cheaper changed that. The reason turned out to be one property of the
V4L2 buffers that nothing in the API advertises, and finding it took four wrong diagnoses.

This is written for anyone reading these cameras on this hardware. The method in the first
section is the reusable part; the cause in the second is specific to the MT2712 but probably
not unique to it.

## Finding out whose fault it is

A capture loop that delivers six frames a second is either being given six or failing to
collect twenty-five, and the two call for opposite work. Three numbers separate them, and all
three are cheap to add.

**`v4l2_buffer.sequence`.** The driver stamps every frame it captures. The gap between one
dequeue's number and the next is exactly what it filled while nobody was collecting. Counting
your own dequeues tells you how many you got and never how many there were.

**Time holding a buffer, and time waiting for one.** Take a timestamp after `VIDIOC_DQBUF` and
another after `VIDIOC_QBUF`, and separately around the `select()`. Waiting long means the
device is slow. Holding long means you are.

**Time for the whole turn of the loop.** Without it the other two cannot be checked, and they
need checking: if `busy + waiting` does not add up to the period, the missing time is real and
it is somewhere neither stopwatch covers. That discrepancy is how both remaining problems here
were found, and ignoring it cost three rebuilds.

What this app saw, before anything was fixed:

```
/dev/video14: 179 frames in 28.2s = 6.4 fps
    driver captured 179 (6.4 fps), we missed 0 | busy 72.3ms, waiting 1.1ms per frame
```

Nothing missed, one millisecond of waiting, seventy-two of holding. The device was never slow.
It was idle, out of buffers, waiting to be given one back.

## The cause: the mapped buffers are not cached

`VIDIOC_REQBUFS` with `V4L2_MEMORY_MMAP` hands back memory that the CPU reads at about
**85 MB/s** on this SoC - roughly a twentieth of ordinary RAM. Copying one 720x240 UYVY cell,
345 KB, takes **3.9 ms**. Nothing in the format, the capability flags or `VIDIOC_QUERYCAP` says
so; it only shows up when timed.

Two consequences follow, and the second is the one worth remembering.

**Read the buffer exactly once, sequentially, into ordinary memory. Then work on the copy.**
Every further pass over the mapped buffer costs another 3.9 ms, and a strided pass costs more
than a linear one.

**Total memory traffic is the wrong thing to optimise.** This app moved its compositor from an
RGBA canvas to NV12, which cut traffic from about 1.17 GB/s to 200 MB/s - and made the frame
rate *worse*. The RGBA version read the mapped buffer once, with a single `cvtColor`. The NV12
version read it three times, once for luma and once for each chroma plane, because that is the
natural way to write the conversion. Six times fewer bytes, three times more of the expensive
kind.

## The other half: housekeeping on the hot path

With the buffer read once, `busy` fell from 72 ms to 8 and capture rose to 19 fps. The period
was still 52 ms, which `busy + waiting` did not explain.

The missing 18 ms per frame was two acquisitions of the session mutex on every single frame -
an exit check at the top of the loop and a sweep for stopped consumers at the bottom - with
four capture threads contending for it. Neither is urgent. Moving them to one frame in eight,
and checking only the atomic stop flag on the other seven, recovered all of it.

## Dead ends

Each of these looked like the answer and was not. They are listed because ruling them out took
a build and a drive each.

| Suspected | Measured |
|---|---|
| The encoder's mutex, held across the encode on the capture thread | Moving the encoder to its own thread changed nothing on its own |
| The hardware encoder not keeping up | `dequeueInputBuffer` costs 2.5 ms |
| A frame interval nobody had set | `VIDIOC_G_PARM` is not supported at all (`ENOTTY`) |
| Too few buffers | The factory app asks for four, the same as this one |
| The preview stealing time | It costs nothing when no surface is attached, and the slow runs had none |

Worth knowing about the factory app, from disassembling `libv4l2utils.so`: it issues
`VIDIOC_S_FMT` and `VIDIOC_S_INPUT(0)`, which this app does not, and it does **not** set a
frame interval either. Neither call turned out to be necessary for the frame rate.

## Two ways to measure a frame rate wrongly

Both of these produced numbers that were confidently reported and wrong.

**Dividing by the time since the first frame.** "Now" is when the report is generated, which
includes walking back to the laptop and opening the app. On a twenty-second run that was a
third of the denominator.

**Dividing by first-to-last.** Better, but it still contains every pause: handing the cameras
to the factory 360 view, or simply nobody asking for a frame between closing a preview and
starting a recording. A twelve-second gap of that kind made 30 fps read as 19.

The rate while capturing is `1 / mean(whole loop)`. Both figures are worth printing - the
second says how much of a drive was actually recorded - but they must not be confused.

## Where it ended up

| | per camera | recorded |
|---|---|---|
| before | 13.7 fps | - |
| after | **29.9 fps** | **24.4 fps** |
| factory 360 app | 25 | 25 |

The cameras deliver thirty frames a second each. The recording is paced to twenty-five and
gets 24.4 of them.

## Still open

The presentation timestamps written into the MP4 are synthetic: every frame is stamped exactly
40 ms after the last, whatever actually arrived. With fewer than twenty-five new frames a
second on a twenty-five frame timeline, some frames are held an extra turn at irregular
intervals, which reads as a stutter worse than an honest lower rate would. The fix belongs in
the timestamps and is independent of everything above.
