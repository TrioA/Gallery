# Full-range face detector

This model locates faces only. AuraFace remains the separate identity embedding model.

- Publisher: Google / MediaPipe
- Official documentation: https://developers.google.com/edge/mediapipe/solutions/vision/face_detector
- Model card and license: https://storage.googleapis.com/mediapipe-assets/MediaPipe%20BlazeFace%20Model%20Card%20(Full%20Range).pdf
- License named by the model card: Apache License, Version 2.0
- Fixed version: https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_full_range/float16/1/blaze_face_full_range.tflite
- SHA-256: `3698b18f063835bc609069ef052228fbe86d9c9a6dc8dcb7c7c2d69aed2b181b`
- Size: 1,083,786 bytes

The existing short-range detector stays primary. If it found no candidates, the full-range detector gets one whole-frame plus tile pass with the same automatic-scan tile budget and confidence thresholds. Full-range accepts face boxes at least 24 pixels across, while primary boxes retain the 48-pixel gate. All assigned identities remain guesses until the user confirms. The embedding model and alignment transform are unchanged. Primary detections are never replaced.

Old files already recorded as scanned are not silently scanned again. Open a photo and use "Look again (more sensitive)" for an explicit retry. A developer reset is a separate destructive action and is not required for this feature. Full-range currently cannot rescue a small companion when the primary detector already found another face; the zero-candidate gate is a CPU and identity-safety tradeoff.

The model card lists identity recognition as out of scope. This model is used for face localisation only. It notes limitations for distant faces, tilted faces, poor lighting, noise and overlap. No broad accuracy claim or phone performance claim follows from the local experiments. No training-data-license claim about AuraFace is changed by this addition.
