# Fixed-size widget mockups

Rendered from the Android RemoteViews implementation at xxhdpi.
Values are synthetic; these are not captures of the owner's live earbuds or a
Samsung launcher. These layouts ship in 0.3.0-preview; the previews use the updated 0.3.4 earbud artwork.

- [Compact, 3 columns × 1 row](compact.png): three battery rings with the supplied
  product images; no visible percentages. Empty/unknown readings have an empty ring.
- [Large, 5 columns × 2 rows](large.png): Left, Right and Case each occupy one
  full-height column, with a ring, image and percentage. The remaining two columns
  contain Bluetooth on top and Sleep below. Selected mode changes color and shape.
  Refresh sits in the top-left corner, above the battery columns. No timestamp or
  Left/Right/Case text labels are shown. Order is left earbud, right earbud, case.

Both widgets disable resizing. Actual dp sizes vary by launcher grid; target spans
are fixed. Refresh and mode changes use the shared bounded action service. Creation,
repainting and host callbacks only render the local cache, without Bluetooth polling.
