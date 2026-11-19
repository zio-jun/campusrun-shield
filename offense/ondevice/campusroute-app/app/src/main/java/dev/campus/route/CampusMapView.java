package dev.campus.route;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class CampusMapView extends View {
    interface OnPoint {
        void select(Geo.Point point);
    }

    private static final int[] MAP_COLORS = {
            0xFF2B483B, 0xFF294539, 0xFF274858, 0xFF3D5B47, 0xFF394641,
            0xFF50655A, 0xFF677A6C, 0xFF8B998B, 0xFFBD866B, 0xFF25362C
    };
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GestureDetector drag;
    private final ScaleGestureDetector pinch;
    private final float density;
    private final ArrayList<RectF> printedLabels = new ArrayList<>();
    private final Path track = new Path();
    private CampusMap map;
    private boolean blank;
    private OnPoint onPoint;
    private Geo.Point start;
    private Geo.Point finish;
    private Geo.Point position;
    private List<Geo.Point> waypoints = Collections.emptyList();
    private Geo.Route route;
    private double centerX;
    private double centerY;
    private boolean moved;
    private boolean multiTouch;
    private float scale;

    CampusMapView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        scale = density * 0.14f;
        setClickable(true);
        setContentDescription("地图。可拖动或双指缩放，选点也可通过下方坐标按钮。");
        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                zoomAt(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
            }
        });
        pinch.setQuickScaleEnabled(false);
        drag = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }

            @Override public boolean onScroll(MotionEvent first, MotionEvent current, float dx, float dy) {
                if (!pinch.isInProgress() && !multiTouch) {
                    centerX += dx / scale;
                    centerY += dy / scale;
                    moved = true;
                    invalidate();
                }
                return true;
            }

            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                if (!multiTouch && onPoint != null) {
                    onPoint.select(unproject(event.getX(), event.getY()));
                    performClick();
                }
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent event) {
                zoomAt(1.7f, event.getX(), event.getY());
                return true;
            }
        });
    }

    @Override public boolean performClick() {
        return super.performClick();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            multiTouch = false;
            getParent().requestDisallowInterceptTouchEvent(true);
        } else if (event.getPointerCount() > 1) {
            multiTouch = true;
        }
        pinch.onTouchEvent(event);
        drag.onTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        return true;
    }

    void setMap(CampusMap map) {
        this.map = map;
        buildTrack();
        if (!moved && start == null && finish == null) {
            overview();
        }
        invalidate();
    }

    /** 空白底图模式：浅色背景 + 网格线，不绘制任何地图图形。 */
    void setBlank(boolean blank) {
        if (this.blank != blank) {
            this.blank = blank;
            invalidate();
        }
    }

    /** 投影原点变化后重建路线轨迹并重新适配视野。 */
    void projectionChanged() {
        buildTrack();
        if (route != null) {
            RectF rect = new RectF();
            track.computeBounds(rect, true);
            fit(rect);
        } else if (start != null || finish != null) {
            focus(start != null ? start : finish);
        } else {
            overview();
        }
        invalidate();
    }

    private void buildTrack() {
        track.rewind();
        if (route != null) {
            Geo.Point first = route.points.get(0);
            track.moveTo((float) CampusMap.x(first), (float) CampusMap.y(first));
            for (int i = 1; i < route.points.size(); i++) {
                Geo.Point point = route.points.get(i);
                track.lineTo((float) CampusMap.x(point), (float) CampusMap.y(point));
            }
        }
    }

    void setOnPoint(OnPoint onPoint) {
        this.onPoint = onPoint;
    }

    void setRoute(Geo.Point start, Geo.Point finish, List<Geo.Point> waypoints, Geo.Route route) {
        this.start = start;
        this.finish = finish;
        this.waypoints = new ArrayList<>(waypoints);
        this.route = route;
        buildTrack();
        invalidate();
    }

    void setPosition(Geo.Point position) {
        if (this.position == position) return;
        this.position = position;
        invalidate();
    }

    void zoom(float factor) {
        zoomAt(factor, getWidth() / 2f, getHeight() / 2f);
    }

    private void zoomAt(float factor, float x, float y) {
        float old = scale;
        scale = Math.max(density * 0.025f, Math.min(density * 6, scale * factor));
        centerX += (x - getWidth() / 2f) * (1 / old - 1 / scale);
        centerY += (y - getHeight() / 2f) * (1 / old - 1 / scale);
        moved = true;
        invalidate();
    }

    void focus(Geo.Point point) {
        centerX = CampusMap.x(point);
        centerY = CampusMap.y(point);
        moved = true;
        invalidate();
    }

    void overview() {
        if (route != null) {
            RectF rect = new RectF();
            track.computeBounds(rect, true);
            fit(rect);
        } else if (start != null || finish != null) {
            focus(start != null ? start : finish);
        } else {
            centerX = 0;
            centerY = 0;
            scale = getWidth() > 0 ? getWidth() / 3_150f : density * 0.14f;
            invalidate();
        }
    }

    private void fit(RectF rect) {
        centerX = rect.centerX();
        centerY = rect.centerY();
        float fitX = (getWidth() - dp(96)) / Math.max(rect.width(), 100);
        float fitY = (getHeight() - dp(120)) / Math.max(rect.height(), 100);
        scale = Math.max(density * 0.025f, Math.min(density * 3, Math.min(fitX, fitY)));
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldWidth, int oldHeight) {
        if (!moved && oldWidth == 0) {
            overview();
        }
    }

    private Geo.Point unproject(float x, float y) {
        return CampusMap.point(centerX + (x - getWidth() / 2f) / scale,
                centerY + (y - getHeight() / 2f) / scale);
    }

    private float screenX(double x) { return (float) ((x - centerX) * scale + getWidth() / 2.0); }
    private float screenY(double y) { return (float) ((y - centerY) * scale + getHeight() / 2.0); }
    private float dp(float d) { return d * density; }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(blank ? 0xFFEEF2ED : 0xFF22302B);
        RectF view = new RectF((float) (centerX - getWidth() / (2 * scale)),
                (float) (centerY - getHeight() / (2 * scale)),
                (float) (centerX + getWidth() / (2 * scale)),
                (float) (centerY + getHeight() / (2 * scale)));
        canvas.save();
        canvas.translate(screenX(0), screenY(0));
        canvas.scale(scale, scale);
        drawGrid(canvas, view);
        if (map != null) {
            canvas.save();
            canvas.clipRect(map.bounds);
            drawMap(canvas, view);
            canvas.restore();
        }
        if (route != null) {
            stroke(0xFF111E17, dp(8) / scale);
            canvas.drawPath(track, paint);
            stroke(Ui.ACCENT, dp(3.2f) / scale);
            canvas.drawPath(track, paint);
        }
        canvas.restore();
        if (map != null) {
            drawLabels(canvas);
        }
        for (int i = 0; i < waypoints.size(); i++) {
            marker(canvas, waypoints.get(i), String.valueOf(i + 1), 0xFFC5E1CF, 10);
        }
        if (start != null && finish != null && Geo.distance(start, finish) < 0.5) {
            marker(canvas, start, "起/终", Ui.ACCENT, 18);
        } else {
            if (start != null) marker(canvas, start, "起", Ui.ACCENT, 13);
            if (finish != null) marker(canvas, finish, "终", Ui.END, 13);
        }
        if (position != null) {
            float x = screenX(CampusMap.x(position));
            float y = screenY(CampusMap.y(position));
            fill(0x60D8FA80);
            canvas.drawCircle(x, y, dp(13), paint);
            fill(Color.WHITE);
            canvas.drawCircle(x, y, dp(7), paint);
            fill(0xFF57874F);
            canvas.drawCircle(x, y, dp(4.5f), paint);
        }
        drawScale(canvas);
    }

    private void drawGrid(Canvas canvas, RectF view) {
        double gap = scale < dp(0.1f) ? 400 : scale < dp(0.6f) ? 200 : 50;
        stroke(blank ? 0xFFD3DCD4 : 0xFF2D3E36, dp(0.7f) / scale);
        for (double x = Math.ceil(view.left / gap) * gap; x < view.right; x += gap) {
            canvas.drawLine((float) x, view.top, (float) x, view.bottom, paint);
        }
        for (double y = Math.ceil(view.top / gap) * gap; y < view.bottom; y += gap) {
            canvas.drawLine(view.left, (float) y, view.right, (float) y, paint);
        }
    }

    private void drawMap(Canvas canvas, RectF view) {
        for (CampusMap.Shape shape : map.shapes) {
            RectF bounds = shape.bounds;
            if (bounds.right < view.left || bounds.left > view.right
                    || bounds.bottom < view.top || bounds.top > view.bottom) {
                continue;
            }
            int kind = shape.kind;
            if (kind < 5 || kind == 9) {
                fill(MAP_COLORS[kind]);
            } else {
                float width = kind == 7 ? Math.max(6, dp(2.3f) / scale)
                        : kind == 6 ? Math.max(3, dp(1.35f) / scale)
                        : Math.max(1.5f, dp(kind == 8 ? 1.9f : 0.85f) / scale);
                stroke(MAP_COLORS[kind], width);
            }
            canvas.drawPath(shape.path, paint);
        }
    }

    private void drawLabels(Canvas canvas) {
        printedLabels.clear();
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(dp(11));
        for (CampusMap.Label label : map.labels) {
            if (label.priority > 0 && scale < dp(0.13f)) {
                continue;
            }
            float x = screenX(label.x);
            float y = screenY(label.y);
            float halfWidth = paint.measureText(label.name) / 2 + dp(4);
            RectF box = new RectF(x - halfWidth, y - dp(15), x + halfWidth, y + dp(6));
            if (box.left < dp(8) || box.right > getWidth() - dp(8)
                    || box.top < dp(47) || box.bottom > getHeight() - dp(30)
                    || overlaps(box)) {
                continue;
            }
            printedLabels.add(box);
            stroke(0xFF21332A, dp(3));
            canvas.drawText(label.name, x, y, paint);
            fill(0xFFE2EADA);
            canvas.drawText(label.name, x, y, paint);
        }
    }

    private boolean overlaps(RectF box) {
        for (RectF prior : printedLabels) {
            if (RectF.intersects(box, prior)) return true;
        }
        return false;
    }

    private void marker(Canvas canvas, Geo.Point point, String label, int color, float radius) {
        float x = screenX(CampusMap.x(point));
        float y = screenY(CampusMap.y(point));
        fill(Ui.BASE);
        canvas.drawCircle(x, y, dp(radius + 2), paint);
        fill(color);
        canvas.drawCircle(x, y, dp(radius), paint);
        fill(Ui.DARK_INK);
        paint.setTextSize(dp(radius > 10 ? 11 : 9));
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(label, x, y - (paint.ascent() + paint.descent()) / 2, paint);
    }

    private void drawScale(Canvas canvas) {
        int meters = 10;
        for (int candidate : new int[]{10, 20, 50, 100, 200, 500, 1_000, 2_000}) {
            if (candidate * scale > dp(72)) break;
            meters = candidate;
        }
        float x = dp(12);
        float y = getHeight() - dp(13);
        int ink = blank ? 0xFF44504A : 0xFFE1EADD;
        stroke(ink, dp(1.4f));
        canvas.drawLine(x, y, x + meters * scale, y, paint);
        canvas.drawLine(x, y, x, y - dp(4), paint);
        canvas.drawLine(x + meters * scale, y, x + meters * scale, y - dp(4), paint);
        fill(ink);
        paint.setTextSize(dp(9));
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(meters + " m", x, y - dp(6), paint);
    }

    private void fill(int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
    }

    private void stroke(int color, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(width);
        paint.setColor(color);
    }
}
