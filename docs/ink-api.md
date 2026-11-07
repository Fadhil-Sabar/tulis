# Verifikasi API androidx.ink 1.0.0

Hasil verifikasi langsung dari sources JAR `1.0.0` Maven Google (`dl.google.com/dl/android/maven2/androidx/ink/`).

## 1. InProgressStrokesView (`androidx.ink.authoring.InProgressStrokesView`)
- Constructor: `InProgressStrokesView(context: Context)`
- `startStroke(event: MotionEvent, pointerId: Int, brush: Brush, motionEventToWorldTransform: Matrix = IDENTITY_MATRIX, strokeToWorldTransform: Matrix = IDENTITY_MATRIX): InProgressStrokeId`
- `addToStroke(event: MotionEvent, pointerId: Int, strokeId: InProgressStrokeId, prediction: MotionEvent? = null)`
- `finishStroke(event: MotionEvent, pointerId: Int, strokeId: InProgressStrokeId)`
- `cancelStroke(strokeId: InProgressStrokeId, event: MotionEvent? = null)`
- `removeFinishedStrokes(strokeIds: Set<InProgressStrokeId>)`
- `addFinishedStrokesListener(listener: InProgressStrokesFinishedListener)`
- `removeFinishedStrokesListener(listener: InProgressStrokesFinishedListener)`
- Callback `InProgressStrokesFinishedListener`:
  `fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>)`

## 2. CanvasStrokeRenderer (`androidx.ink.rendering.android.canvas.CanvasStrokeRenderer`)
- Factory: `CanvasStrokeRenderer.create(): CanvasStrokeRenderer`
- Draw: `draw(canvas: Canvas, stroke: Stroke, strokeToScreenTransform: Matrix)` (juga overload `AffineTransform`)
  Catatan: `CanvasStrokeRenderer.draw` menerima `Matrix` Android langsung.

## 3. Brush & StockBrushes (`androidx.ink.brush.*`)
- `StockBrushes.pressurePen(): BrushFamily`
- `StockBrushes.marker(): BrushFamily`
- `StockBrushes.highlighter(selfOverlap: SelfOverlap = SelfOverlap.ANY): BrushFamily`
- `Brush.createWithColorIntArgb(family: BrushFamily, @ColorInt colorIntArgb: Int, size: Float, epsilon: Float = 0.1f): Brush`
  Keterangan stabilo: warna stabilo memerlukan alpha (mis. `(colorArgb and 0x00FFFFFF) or 0x66000000`) karena `StockBrushes.highlighter()` mengandalkan warna semi-transparan dari pengguna.

## 4. Stroke & StrokeInputBatch (`androidx.ink.strokes.*`)
- `Stroke(brush: Brush, inputs: StrokeInputBatch)`: menghasilkan Stroke beserta PartitionedMesh shape.
- `stroke.brush: Brush`
- `stroke.inputs: ImmutableStrokeInputBatch`
- `stroke.shape: PartitionedMesh`
- `stroke.shape.computeBoundingBox(): Box?`
  `Box` memiliki `xMin`, `yMin`, `xMax`, `yMax`.
- `inputs.size: Int`
- `inputs.populate(index: Int, outStrokeInput: StrokeInput): StrokeInput`
- `StrokeInput`: properti `x`, `y` bertipe `Float`.

## 5. Serialisasi (`androidx.ink.storage.*`)
- `StrokeInputBatch.encode(output: OutputStream)` (extension function pada `StrokeInputBatch`)
  Mengompres format proto biner dengan GZIP.
- `StrokeInputBatch.decode(input: InputStream): ImmutableStrokeInputBatch` (extension function pada `StrokeInputBatch.Companion`)
