---
name: character-sheet-design
description: Design character identity and outfit reference images, including single-sheet front/side/back views, separate head portraits, costume details, and transparent or white backgrounds. Use before generating character or character-look design images; follow an explicit portrait or other layout request when provided.
---

# Character Sheet Design

Turn the creator's character brief into an image-generation prompt and a verifiable reference image. The external agent generates the image with its available image tool; FREYJA MCP uploads and associates the result.

## Preserve the brief

- Read the character, selected look and existing reference images. Keep stable identity (face, age, build) distinct from the selected look (clothing, hair, makeup and accessories).
- Preserve the creator's requested style, layout, views, details and background. A request for a single portrait stays a single portrait; the sheet layout below applies when a character sheet or multi-view design is requested.
- Keep unknown identity facts and visual states unspecified. Use existing references consistently rather than inventing a new identity for each view.

## One-sheet multi-view layout

For a request for three views, details, and separate head/body areas on one image:

- Produce one image for one character in one selected look. Arrange full-body front, side and back views in a main row, at a consistent scale and ground line. Include all of each figure, with clear space between the views.
- Place larger head portraits in a separate region of the same canvas, with a front and side portrait when space allows. Head/body separation means separate portrait and full-body regions; do not depict severed anatomy.
- Add close-ups of the requested or established visual details, such as a costume fastening, embroidery, material, accessory or distinctive facial feature. Keep each detail consistent with the main views.
- Use neutral poses and even studio lighting that reveal the design. Preserve face, proportions, outfit, colors and accessories across views. Allocate enough canvas area for legible portraits and details.
- Treat the repeated views as depictions of the same character, not a crowd. Avoid a story scene, decorative environment, text labels or watermarks unless requested.

## Background and export

- If transparency is requested, request actual alpha transparency through the image tool's output option as well as the prompt, and save a PNG with alpha. For a tool exposing `transparent_background`, set it to `true`.
- A checkerboard drawn into an opaque image is not a transparent background. Inspect the image and, when file tools permit, its alpha channel before claiming transparency.
- If the creator allows either transparent or white, prefer real transparency when the tool supports it; otherwise use a clean solid white background. White means white, without an environment, gradient, colored border or simulated transparency grid.
- If the creator requires transparency and the current tool cannot produce it, report that limit or use an available background-removal capability within the authorized scope; do not silently substitute white.

## Prompt construction

Include the established identity and selected-look description, requested rendering style, exact panel arrangement, consistency constraints, detail subjects, lighting and background/export requirements in the generation prompt.

Example layout clause, to combine with the actual character brief:

> One character reference sheet on a single canvas. Main region: full-body front, side and back views of the same character wearing the same outfit, aligned at equal scale. Separate upper region: enlarged front and side head portraits. Detail region: close-ups of the established costume fastening and accessory. Consistent face, proportions, colors and materials in every panel; neutral poses, even studio lighting, clear spacing, complete uncropped figures. Actual transparent PNG background, no painted checkerboard, no scenery, no labels or watermark.

Replace the background clause with `clean solid white background` when using the allowed white alternative. Adapt detail subjects to the real brief; do not copy the example's accessories into a character that has none.

## Check and associate

Inspect the generated result against the requested views, separate portrait region, detail readability, identity/outfit consistency, cropping and background. Correct demonstrated defects within the user's scope; do not report the prompt alone as proof that the output complies.

Upload with `upload_asset_image` or `batch_upload_asset_images`, then associate the returned URL with the correct character or character-look `referenceImageUrl`. Read back the record to verify the association. Keep layout/export instructions in the generation prompt; identity `appearancePrompt` and look `outfitPrompt` retain their own subject-specific meaning.

When using this sheet as a later video reference, explicitly tell the video prompt that its panels represent one character. The video should not reproduce the sheet's panel layout or multiply the character unless the creator requests it. Follow the selected video model's own writing skill for the final video prompt.
