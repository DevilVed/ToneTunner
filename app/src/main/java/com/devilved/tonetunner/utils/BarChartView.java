package com.devilved.tonetunner.utils;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

/**
 * A small bar chart for the usage dashboard: one baseline, optional stacked series, thin bars
 * with rounded data ends, a 2 dp surface gap between stacked segments, a recessive grid and
 * selective axis labels. Tapping a bar shows its values. The content description carries every
 * value so screen readers get the full table.
 */
public class BarChartView extends View {

    /** Formats one value for the tooltip and axis. */
    public interface Formatter {
        String format(double value);
    }

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strongTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float[] radii = new float[8];
    private final float density;

    private String[] labels = new String[0];
    private double[][] series = new double[0][];
    private String[] seriesNames = new String[0];
    private int[] colors = new int[0];
    private Formatter formatter = String::valueOf;
    private int highlightStart = -1;
    private int highlightLength;
    private int highlightColor;
    private String emptyText = "";
    private int selected = -1;

    public BarChartView(Context context) {
        this(context, null);
    }

    public BarChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        textPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11,
                getResources().getDisplayMetrics()));
        strongTextPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13,
                getResources().getDisplayMetrics()));
        strongTextPaint.setFakeBoldText(true);
        gridPaint.setStrokeWidth(Math.max(1, density));
        setFocusable(true);
    }

    /** Text and grid colors come from the theme; series colors are passed with the data. */
    public void setInk(int primaryText, int secondaryText, int grid) {
        strongTextPaint.setColor(primaryText);
        textPaint.setColor(secondaryText);
        gridPaint.setColor(grid);
        invalidate();
    }

    /**
     * @param series values as [series][bar]; stacked bottom to top in the given order
     */
    public void setData(String[] labels, double[][] series, String[] seriesNames, int[] colors,
                        Formatter formatter, String emptyText) {
        this.labels = labels == null ? new String[0] : labels;
        this.series = series == null ? new double[0][] : series;
        this.seriesNames = seriesNames == null ? new String[0] : seriesNames;
        this.colors = colors == null ? new int[0] : colors;
        this.formatter = formatter == null ? String::valueOf : formatter;
        this.emptyText = emptyText == null ? "" : emptyText;
        selected = -1;
        updateDescription();
        invalidate();
    }

    /** Draws bars [start, start + length) in a highlight color, wrapping at the end (peak hours). */
    public void setHighlight(int start, int length, int color) {
        highlightStart = start;
        highlightLength = length;
        highlightColor = color;
        invalidate();
    }

    public int getSelectedIndex() {
        return selected;
    }

    double total(int bar) {
        double sum = 0;
        for (double[] values : series) if (bar < values.length && values[bar] > 0) sum += values[bar];
        return sum;
    }

    boolean hasData() {
        for (int i = 0; i < labels.length; i++) if (total(i) > 0) return true;
        return false;
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = (int) (176 * density);
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = getPaddingLeft();
        float right = getWidth() - getPaddingRight();
        float top = getPaddingTop() + 22 * density;              // room for the tooltip line
        float bottom = getHeight() - getPaddingBottom() - 18 * density; // room for axis labels
        int count = labels.length;
        if (count == 0 || right <= left) return;

        double max = 0;
        for (int i = 0; i < count; i++) max = Math.max(max, total(i));

        // Recessive grid: baseline plus a half and top line, labelled at the left edge.
        for (int line = 0; line <= 2; line++) {
            float y = bottom - (bottom - top) * line / 2f;
            canvas.drawLine(left, y, right, y, gridPaint);
            if (line > 0 && max > 0) {
                canvas.drawText(formatter.format(max * line / 2.0), left, y - 3 * density, textPaint);
            }
        }
        if (max <= 0) {
            float width = textPaint.measureText(emptyText);
            canvas.drawText(emptyText, (left + right - width) / 2, (top + bottom) / 2, textPaint);
            return;
        }

        float slot = (right - left) / count;
        float barWidth = Math.min(24 * density, slot * 0.64f);
        float gap = 2 * density;
        float radius = Math.min(4 * density, barWidth / 2);
        for (int i = 0; i < count; i++) {
            float x0 = left + slot * i + (slot - barWidth) / 2;
            float y = bottom;
            int topSegment = -1;
            for (int s = 0; s < series.length; s++) if (i < series[s].length && series[s][i] > 0) topSegment = s;
            for (int s = 0; s < series.length; s++) {
                double value = i < series[s].length ? series[s][i] : 0;
                if (value <= 0) continue;
                float height = (float) ((bottom - top) * value / max);
                float segmentTop = y - height;
                boolean isTop = s == topSegment;
                float drawnBottom = y == bottom ? y : y - gap;  // surface gap between stacked segments
                if (drawnBottom - segmentTop < 1) segmentTop = drawnBottom - 1;
                int color = isHighlighted(i) ? highlightColor : colors[Math.min(s, colors.length - 1)];
                barPaint.setColor(color);
                barPaint.setAlpha(selected >= 0 && selected != i ? 140 : 255);
                rect.set(x0, segmentTop, x0 + barWidth, drawnBottom);
                if (isTop) drawTopRounded(canvas, rect, radius);
                else canvas.drawRect(rect, barPaint);
                y -= height;
            }
        }

        // Selective axis labels: at most about seven, always including the first and last bar.
        int step = Math.max(1, (int) Math.ceil(count / 7.0));
        for (int i = 0; i < count; i++) {
            if (i % step != 0 && i != count - 1) continue;
            if (i != count - 1 && count - 1 - i < step && i != 0) continue;
            String label = labels[i];
            float width = textPaint.measureText(label);
            float x = left + slot * i + slot / 2 - width / 2;
            x = Math.max(left, Math.min(right - width, x));
            canvas.drawText(label, x, getHeight() - getPaddingBottom() - 4 * density, textPaint);
        }

        if (selected >= 0 && selected < count) {
            String tip = tooltip(selected);
            float width = strongTextPaint.measureText(tip);
            float x = left + slot * selected + slot / 2 - width / 2;
            x = Math.max(left, Math.min(right - width, x));
            canvas.drawText(tip, x, getPaddingTop() + 14 * density, strongTextPaint);
        }
    }

    private void drawTopRounded(Canvas canvas, RectF r, float radius) {
        float rr = Math.min(radius, r.height());
        // Rounded data end at the top, square at the baseline.
        radii[0] = radii[1] = radii[2] = radii[3] = rr;
        path.reset();
        path.addRoundRect(r, radii, Path.Direction.CW);
        canvas.drawPath(path, barPaint);
    }

    private boolean isHighlighted(int index) {
        if (highlightStart < 0 || highlightLength <= 0 || labels.length == 0) return false;
        int offset = (index - highlightStart + labels.length) % labels.length;
        return offset < highlightLength;
    }

    String tooltip(int index) {
        StringBuilder text = new StringBuilder(labels[index]).append(": ");
        if (series.length == 1) return text.append(formatter.format(total(index))).toString();
        for (int s = 0; s < series.length; s++) {
            if (s > 0) text.append(" · ");
            String name = s < seriesNames.length ? seriesNames[s] : "";
            double value = index < series[s].length ? series[s][index] : 0;
            text.append(name).append(' ').append(formatter.format(value));
        }
        return text.toString();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (labels.length == 0) return super.onTouchEvent(event);
        if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
            getParent().requestDisallowInterceptTouchEvent(event.getAction() == MotionEvent.ACTION_MOVE);
            float width = getWidth() - getPaddingLeft() - getPaddingRight();
            int index = (int) ((event.getX() - getPaddingLeft()) / (width / labels.length));
            index = Math.max(0, Math.min(labels.length - 1, index));
            if (index != selected) {
                selected = index;
                announceForAccessibility(tooltip(index));
                invalidate();
            }
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP) performClick();
        return true;
    }

    @Override public boolean performClick() {
        return super.performClick();
    }

    private void updateDescription() {
        StringBuilder text = new StringBuilder();
        if (!hasData()) {
            text.append(emptyText);
        } else {
            for (int i = 0; i < labels.length; i++) {
                if (total(i) <= 0) continue;
                if (text.length() > 0) text.append("; ");
                text.append(tooltip(i));
            }
        }
        setContentDescription(text.toString());
    }
}
