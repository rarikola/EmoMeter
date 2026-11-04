# EmoMeter

Live front-camera feed with Plutchik's emotions as four two-sided meters:

| left | | right |
|---|---|---|
| rage · anger · annoyance | neutral | apprehension · fear · terror |
| vigilance · anticipation · interest | neutral | distraction · surprise · amazement |
| ecstasy · joy · serenity | neutral | pensiveness · sadness · grief |
| admiration · trust · acceptance | neutral | boredom · disgust · loathing |

Neutral is in the middle; the further out, the stronger. The closest segment lights up and a white
needle shows the exact position. Below the bars, the 8 blends (love, awe, contempt…) light up when
both of their neighbouring emotions are active.

## How it works

1. MediaPipe Face Landmarker gives 52 blendshape scores per frame (brow down, smile, jaw open…).
   Blinks and gaze direction are ignored.
2. **Teach** records your neutral face, then one face per emotion (3 s to get ready, 1.5 s recorded
   and averaged). Each emotion is stored as the *change* from your neutral face.
3. Each frame, the change from neutral is split into a non-negative mix of the 8 taught faces.
   A strength of 1.0 means "as strong as when you taught it". Overlapping emotions (joy and trust
   both smile) share the work instead of both firing.
4. Strengths are smoothed (~0.25 s) so the needles don't jitter.

Before teaching, rough default faces are used. **Skip** keeps the current face for that emotion;
**Reset** returns to the defaults.

## Poker face (game)

Pictures flash on screen for 3 s each (1.5 s relax gap between), 20 per round. The meter is
hidden; every leak costs points (mild 1, clear 3, strong 5, face out of view 5). A 3 s baseline at
the start makes the player's relaxed face, in the current light, the neutral for that round.
The results show the score, the biggest leak with a frozen frame, and every picture.

Pictures go in `app/src/main/assets/stimuli/`, listed in `stimuli/list.csv`:

```
file,emotion,label
puppy01.jpg,joy,Puppy
```

`emotion` is one of: joy, trust, fear, surprise, sadness, disgust, anger, anticipation, neutral.
Unseen pictures are shown first, so the whole collection rotates before anything repeats.
The `test01–test20` cards are placeholders for trying the flow.

## Build

GitHub Actions builds the APK on every push (the face model is downloaded during the build).
Open the **Actions** tab → latest run → download **EmoMeter-apk**.

Note: joy, sadness, anger, fear, surprise and disgust show clearly on a face. Trust and anticipation
barely do, so those meters lean on whatever overlaps with them.
