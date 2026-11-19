package dev.campus.route;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int ASK_LOCATION = 10;
    private static final int ASK_NOTIFICATIONS = 11;
    private static final int PICK_MAP = 20;
    private static final int MAX_WAYPOINTS = 24;
    private static final String CAMPUS_APP = "net.crigh.hzkjsport";
    private static final String MAP_FILE = "custom-map.json";

    private enum Selection { START, FINISH, WAYPOINT, NONE }

    private enum MapMode { CUSTOM, BUILTIN, BLANK }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Geo.Point> waypoints = new ArrayList<>();
    private Geo.Point start;
    private Geo.Point finish;
    private CampusMap offlineMap;
    private MapMode mapMode = MapMode.BLANK;
    private String customMapName;
    private boolean blankOriginSet;
    private Geo.Route route;
    private Geo.Route displayedRoute;
    private Selection selection = Selection.START;
    private boolean alongRoads = true;
    private boolean mapLoading = true;
    private int directLegs;
    private long previewStart;
    private double previewSpeed;
    private Geo.Route previewRoute;
    private boolean previewing;
    private boolean previewDone;
    private long preparingAt;
    private PlaybackService.Status priorStatus;
    private PlaybackService.Status ignoredStatus;

    private CampusMapView map;
    private TextView mapHint;
    private TextView subtitle;
    private TextView attribution;
    private Button mapModeButton;
    private TextView distanceValue;
    private TextView timeValue;
    private TextView paceValue;
    private TextView routeDescription;
    private TextView status;
    private Button startLabel;
    private Button finishLabel;
    private Button startCoordinates;
    private Button finishCoordinates;
    private Button reset;
    private Button routing;
    private Button addWaypoint;
    private Button undoWaypoint;
    private EditText speed;
    private Button preview;
    private Button primary;
    private Button headerAction;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            updatePlayback();
            ui.postDelayed(this, 220);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        restoreFields();
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        setContentView(createContent());
        recomputeRoute();
        loadMap();
    }

    /** 地图加载顺序：用户导入 > 内置华科地图 > 空白模式。 */
    private void loadMap() {
        mapLoading = true;
        recomputeRoute();
        new Thread(() -> {
            CampusMap loaded = null;
            MapMode mode = MapMode.BLANK;
            String name = null;
            if (!"blank".equals(prefs().getString("mapMode", "auto"))) {
                try {
                    File file = new File(getFilesDir(), MAP_FILE);
                    if (file.exists()) {
                        loaded = CampusMap.parse(readText(file));
                        mode = MapMode.CUSTOM;
                        name = prefs().getString("mapName", null);
                    }
                } catch (Exception error) {
                    Log.w("CampusRoute", "The imported map could not be loaded", error);
                    loaded = null;
                }
                if (loaded == null) {
                    try {
                        loaded = CampusMap.load(getAssets());
                        mode = MapMode.BUILTIN;
                    } catch (Exception error) {
                        Log.w("CampusRoute", "The built-in map could not be loaded", error);
                    }
                }
            }
            CampusMap result = loaded;
            MapMode resultMode = mode;
            String resultName = name;
            ui.post(() -> {
                if (isDestroyed()) return;
                applyMap(result, resultMode, resultName);
            });
        }, "offline-campus-map").start();
    }

    private void applyMap(CampusMap result, MapMode mode, String name) {
        offlineMap = result;
        mapLoading = false;
        mapMode = mode;
        customMapName = name;
        if (result != null) {
            CampusMap.setOrigin(result.mapOriginLat, result.mapOriginLon);
        } else {
            setBlankOriginFromRoute();
        }
        boolean blank = result == null;
        map.setBlank(blank);
        if (attribution != null) {
            attribution.setVisibility(blank ? View.GONE : View.VISIBLE);
        }
        map.setMap(result);
        map.overview();
        updateSubtitle();
        recomputeRoute();
    }

    private void setBlankOriginFromRoute() {
        Geo.Point base = start != null ? start : finish != null ? finish
                : !waypoints.isEmpty() ? waypoints.get(0) : null;
        if (base != null) {
            CampusMap.setOrigin(base.latitude, base.longitude);
            blankOriginSet = true;
        }
    }

    /** 空白模式下首个选点即作为投影原点，保证本地平面坐标不失真。 */
    private void ensureBlankOrigin(Geo.Point point) {
        if (offlineMap == null && !blankOriginSet) {
            CampusMap.setOrigin(point.latitude, point.longitude);
            blankOriginSet = true;
            map.projectionChanged();
        }
    }

    private void updateSubtitle() {
        if (subtitle == null) return;
        String value;
        if (mapMode == MapMode.CUSTOM) {
            value = "自定义地图" + (customMapName != null && !customMapName.isEmpty()
                    ? " · " + customMapName : "");
        } else if (mapMode == MapMode.BUILTIN) {
            value = "华科主校区 · 离线地图";
        } else {
            value = "空白地图 · 自由选点";
        }
        text(subtitle, value);
        if (mapModeButton != null) {
            text(mapModeButton, mapMode == MapMode.BLANK ? "自动地图" : "空白地图");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        ui.removeCallbacks(poll);
        ui.post(poll);
    }

    @Override protected void onPause() {
        ui.removeCallbacks(poll);
        saveFields();
        super.onPause();
    }

    private View createContent() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BASE);
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets reserved = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(reserved.left, reserved.top, reserved.right, reserved.bottom);
                return insets;
            });
        }
        root.addView(header());
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        LinearLayout content = Ui.column(this);
        content.setPadding(dp(18), dp(5), dp(18), dp(14));
        content.setFocusableInTouchMode(true);
        scroll.addView(content, new ScrollView.LayoutParams(MATCH, WRAP));

        int screen = getResources().getConfiguration().screenHeightDp;
        int mapHeight = Math.min(320, Math.max(210, (int) (screen * 0.328)));
        content.addView(mapCard(), new LinearLayout.LayoutParams(MATCH, dp(mapHeight)));
        addBelow(content, metrics(), 12);
        addBelow(content, routeControls(), 13);
        status = Ui.text(this, "", 13, Ui.MUTED);
        status.setPadding(dp(19), dp(8), dp(19), dp(2));
        status.setVisibility(View.GONE);
        root.addView(status, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout footer = Ui.row(this);
        footer.setBackgroundColor(Ui.BASE);
        footer.setPadding(dp(18), dp(9), dp(18), dp(11));
        preview = button("预览", this::previewOrStop);
        primary = button("准备系统定位", this::primaryAction);
        footer.addView(preview, new LinearLayout.LayoutParams(0, dp(53), 0.95f));
        LinearLayout.LayoutParams primarySize = new LinearLayout.LayoutParams(0, dp(53), 1.85f);
        primarySize.leftMargin = dp(9);
        footer.addView(primary, primarySize);
        root.addView(footer, new LinearLayout.LayoutParams(MATCH, WRAP));
        content.requestFocus();
        return root;
    }

    private View header() {
        LinearLayout header = Ui.row(this);
        header.setPadding(dp(18), dp(13), dp(18), dp(12));
        LinearLayout titles = Ui.column(this);
        TextView title = Ui.text(this, "校园路线", 29, Ui.INK);
        Ui.medium(title);
        title.setLetterSpacing(0.025f);
        titles.addView(title);
        subtitle = Ui.text(this, "华科主校区 · 离线地图", 12, Ui.MUTED);
        addBelow(titles, subtitle, 5);
        header.addView(titles, new LinearLayout.LayoutParams(0, WRAP, 1));
        headerAction = button("使用说明", () -> {
            if (PlaybackService.status().active()) openCampusApp();
            else showHelp();
        });
        headerAction.setTextSize(12);
        header.addView(headerAction, new LinearLayout.LayoutParams(dp(117), dp(42)));
        return header;
    }

    private View mapCard() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(Ui.background(this, Ui.PANEL, 20, 0));
        frame.setClipToOutline(true);

        map = new CampusMapView(this);
        map.setOnPoint(point -> choosePoint(point, false));
        frame.addView(map, new FrameLayout.LayoutParams(MATCH, MATCH));
        mapHint = Ui.text(this, "点按地图选起点", 12, Ui.INK);
        Ui.medium(mapHint);
        mapHint.setGravity(Gravity.CENTER_VERTICAL);
        mapHint.setPadding(dp(10), 0, dp(10), 0);
        mapHint.setBackground(Ui.background(this, 0xEA18241E, 9, 0));
        FrameLayout.LayoutParams hintSize = new FrameLayout.LayoutParams(WRAP, dp(34), Gravity.TOP | Gravity.LEFT);
        hintSize.setMargins(dp(11), dp(11), dp(69), 0);
        frame.addView(mapHint, hintSize);

        Button overview = button("全览", () -> map.overview());
        overview.setTextSize(12);
        FrameLayout.LayoutParams overviewSize = new FrameLayout.LayoutParams(dp(53), dp(39), Gravity.TOP | Gravity.RIGHT);
        overviewSize.setMargins(0, dp(9), dp(9), 0);
        frame.addView(overview, overviewSize);

        mapModeButton = button("空白地图", () -> {
            boolean forceBlank = "blank".equals(prefs().getString("mapMode", "auto"));
            prefs().edit().putString("mapMode", forceBlank ? "auto" : "blank").apply();
            loadMap();
        });
        mapModeButton.setTextSize(12);
        FrameLayout.LayoutParams modeSize = new FrameLayout.LayoutParams(dp(63), dp(39), Gravity.TOP | Gravity.RIGHT);
        modeSize.setMargins(0, dp(9), dp(68), 0);
        frame.addView(mapModeButton, modeSize);

        Button importMap = button("导入地图", () -> pickMapFile());
        importMap.setTextSize(12);
        FrameLayout.LayoutParams importSize = new FrameLayout.LayoutParams(dp(86), dp(34), Gravity.BOTTOM | Gravity.LEFT);
        importSize.setMargins(dp(9), 0, 0, dp(9));
        frame.addView(importMap, importSize);

        LinearLayout zoom = Ui.column(this);
        Button zoomIn = button("＋", () -> map.zoom(1.6f));
        Button zoomOut = button("−", () -> map.zoom(1 / 1.6f));
        zoomIn.setTextSize(23);
        zoomOut.setTextSize(23);
        zoomIn.setContentDescription("放大地图");
        zoomOut.setContentDescription("缩小地图");
        zoom.addView(zoomIn, new LinearLayout.LayoutParams(dp(43), dp(43)));
        LinearLayout.LayoutParams zoomOutSize = new LinearLayout.LayoutParams(dp(43), dp(43));
        zoomOutSize.topMargin = dp(3);
        zoom.addView(zoomOut, zoomOutSize);
        FrameLayout.LayoutParams zoomSize = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.RIGHT);
        zoomSize.setMargins(0, 0, dp(9), dp(34));
        frame.addView(zoom, zoomSize);

        TextView attribution = Ui.text(this, "© OpenStreetMap contributors · ODbL", 9, Ui.INK);
        attribution.setBackground(Ui.background(this, 0xE717211C, 5, 0));
        attribution.setPadding(dp(6), 0, dp(6), 0);
        attribution.setOnClickListener(v -> launch(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.openstreetmap.org/copyright")), "请在浏览器访问 openstreetmap.org/copyright"));
        this.attribution = attribution;
        FrameLayout.LayoutParams creditSize = new FrameLayout.LayoutParams(WRAP, dp(22), Gravity.BOTTOM | Gravity.RIGHT);
        creditSize.setMargins(0, 0, dp(7), dp(5));
        frame.addView(attribution, creditSize);
        return frame;
    }

    private View metrics() {
        LinearLayout row = Ui.row(this);
        row.setBackground(Ui.background(this, Ui.PANEL, 15, Ui.LINE));
        row.setPadding(dp(4), dp(10), dp(4), dp(10));
        distanceValue = metric(row, "路线距离");
        timeValue = metric(row, "预计用时");
        paceValue = metric(row, "配速 / km");
        return row;
    }

    private TextView metric(LinearLayout row, String name) {
        if (row.getChildCount() != 0) {
            View rule = new View(this);
            rule.setBackgroundColor(Ui.LINE);
            row.addView(rule, new LinearLayout.LayoutParams(dp(1), dp(29)));
        }
        LinearLayout cell = Ui.column(this);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView value = Ui.text(this, "—", 21, Ui.ACCENT);
        Ui.medium(value);
        value.setMaxLines(1);
        value.setAutoSizeTextTypeUniformWithConfiguration(12, 21, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        cell.addView(value, new LinearLayout.LayoutParams(MATCH, dp(27)));
        value.setGravity(Gravity.CENTER);
        addBelow(cell, Ui.text(this, name, 11, Ui.MUTED), 4);
        row.addView(cell, new LinearLayout.LayoutParams(0, WRAP, 1));
        return value;
    }

    private View routeControls() {
        LinearLayout panel = Ui.column(this);
        panel.setPadding(dp(12), dp(8), dp(12), dp(12));
        panel.setBackground(Ui.background(this, Ui.PANEL, 16, Ui.LINE));

        LinearLayout title = Ui.row(this);
        TextView label = Ui.text(this, "路线设置", 15, Ui.INK);
        Ui.medium(label);
        title.addView(label, new LinearLayout.LayoutParams(0, WRAP, 1));
        reset = button("重置", this::resetRoute);
        reset.setTextSize(12);
        title.addView(reset, new LinearLayout.LayoutParams(dp(62), dp(36)));
        panel.addView(title);

        startLabel = button("起点", () -> selectOnMap(Selection.START));
        finishLabel = button("终点", () -> selectOnMap(Selection.FINISH));
        startCoordinates = button("坐标", () -> coordinateDialog(Selection.START));
        finishCoordinates = button("坐标", () -> coordinateDialog(Selection.FINISH));
        startCoordinates.setContentDescription("输入起点坐标");
        finishCoordinates.setContentDescription("输入终点坐标");
        addBelow(panel, pointRow(startLabel, startCoordinates), 7);
        addBelow(panel, pointRow(finishLabel, finishCoordinates), 5);

        LinearLayout options = Ui.row(this);
        routing = button("沿道路", () -> {
            alongRoads = !alongRoads;
            routeEdited();
        });
        addWaypoint = button("加途经点", () -> selectOnMap(selection == Selection.WAYPOINT
                ? Selection.NONE : Selection.WAYPOINT));
        undoWaypoint = button("撤回", () -> {
            if (!waypoints.isEmpty()) {
                waypoints.remove(waypoints.size() - 1);
                routeEdited();
            }
        });
        undoWaypoint.setContentDescription("撤回最后一个途经点");
        for (Button option : new Button[]{routing, addWaypoint, undoWaypoint}) {
            option.setTextSize(12);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(41), 1);
            if (options.getChildCount() > 0) size.leftMargin = dp(6);
            options.addView(option, size);
        }
        addBelow(panel, options, 8);

        routeDescription = Ui.text(this, "", 11, Ui.MUTED);
        routeDescription.setPadding(dp(1), dp(2), 0, dp(2));
        addBelow(panel, routeDescription, 6);

        LinearLayout speedRow = Ui.row(this);
        TextView speedLabel = Ui.text(this, "模拟速度", 14, Ui.INK);
        Ui.medium(speedLabel);
        speedRow.addView(speedLabel, new LinearLayout.LayoutParams(0, WRAP, 1));
        speed = new EditText(this);
        speed.setTextSize(17);
        speed.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        speed.setTextColor(Ui.ACCENT);
        speed.setSingleLine(true);
        speed.setSelectAllOnFocus(true);
        speed.setGravity(Gravity.CENTER);
        speed.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        speed.setImeOptions(EditorInfo.IME_ACTION_DONE);
        speed.setBackground(Ui.background(this, Ui.BASE, 8, Ui.LINE));
        speed.setPadding(dp(7), 0, dp(7), 0);
        speed.setContentDescription("速度，单位每小时公里");
        speed.setText(prefs().getString("speed", "9.0"));
        speed.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                syncMetrics();
                updatePlayback();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        speedRow.addView(speed, new LinearLayout.LayoutParams(dp(85), dp(44)));
        TextView unit = Ui.text(this, "km/h", 13, Ui.MUTED);
        unit.setPadding(dp(8), 0, dp(1), 0);
        speedRow.addView(unit);
        LinearLayout.LayoutParams speedLayout = new LinearLayout.LayoutParams(MATCH, WRAP);
        speedLayout.topMargin = dp(8);
        panel.addView(speedRow, panel.indexOfChild(options), speedLayout);
        return panel;
    }

    private View pointRow(Button point, Button coordinates) {
        LinearLayout row = Ui.row(this);
        point.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        point.setTextSize(13);
        row.addView(point, new LinearLayout.LayoutParams(0, dp(43), 1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(62), dp(43));
        p.leftMargin = dp(6);
        coordinates.setTextSize(12);
        row.addView(coordinates, p);
        return row;
    }

    private int dp(float size) { return Ui.dp(this, size); }

    private Button button(String label, Runnable onClick) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(14);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setIncludeFontPadding(false);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setStateListAnimator(null);
        Ui.medium(button);
        Ui.styleButton(button, Ui.PANEL, Ui.INK, Ui.LINE, 11);
        button.setOnClickListener(v -> onClick.run());
        return button;
    }

    private void addBelow(LinearLayout parent, View child, int gap) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(MATCH, WRAP);
        size.topMargin = dp(gap);
        parent.addView(child, size);
    }

    private void text(TextView view, String value) {
        if (!value.contentEquals(view.getText())) view.setText(value);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("campus-route", MODE_PRIVATE);
    }

    private void restoreFields() {
        alongRoads = prefs().getBoolean("alongRoads", true);
        try {
            JSONArray stored = new JSONArray(prefs().getString("points", "[null,null,[]]"));
            start = readPoint(stored.optJSONArray(0));
            finish = readPoint(stored.optJSONArray(1));
            JSONArray steps = stored.getJSONArray(2);
            for (int i = 0; i < steps.length(); i++) {
                Geo.Point item = readPoint(steps.getJSONArray(i));
                if (item != null) waypoints.add(item);
            }
        } catch (JSONException | IllegalArgumentException error) {
            Log.w("CampusRoute", "Could not load saved route", error);
        }
        selection = start == null ? Selection.START : finish == null ? Selection.FINISH : Selection.NONE;
    }

    private Geo.Point readPoint(JSONArray array) throws JSONException {
        if (array == null) return null;
        return new Geo.Point(array.getDouble(0), array.getDouble(1));
    }

    private JSONArray savePoint(Geo.Point point) {
        if (point == null) return null;
        return new JSONArray(Arrays.asList(point.latitude, point.longitude));
    }

    private void saveFields() {
        if (speed == null) return;
        JSONArray steps = new JSONArray();
        for (Geo.Point point : waypoints) steps.put(savePoint(point));
        JSONArray all = new JSONArray().put(savePoint(start)).put(savePoint(finish)).put(steps);
        prefs().edit().putString("points", all.toString()).putBoolean("alongRoads", alongRoads)
                .putString("speed", speed.getText().toString().trim()).apply();
    }

    private boolean controlsLocked() {
        return previewing || preparingAt != 0 || PlaybackService.status().active();
    }

    private void selectOnMap(Selection target) {
        if (controlsLocked()) return;
        selection = target;
        if (target == Selection.START && start != null) map.focus(start);
        if (target == Selection.FINISH && finish != null) map.focus(finish);
        updateSelection();
    }

    private void updateSelection() {
        String hint;
        if (previewing) hint = "预览中";
        else if (PlaybackService.status().active()) hint = "系统模拟定位";
        else if (selection == Selection.START) hint = "点按地图选起点";
        else if (selection == Selection.FINISH) hint = "点按地图选终点";
        else if (selection == Selection.WAYPOINT) hint = "按经过顺序点选途经点";
        else hint = "拖动地图 · 双指缩放";
        text(mapHint, hint);
        text(addWaypoint, selection == Selection.WAYPOINT ? "完成选点" : "加途经点");
    }

    private void choosePoint(Geo.Point point, boolean focus) {
        if (controlsLocked() || selection == Selection.NONE) return;
        if (selection == Selection.START) {
            start = point;
            selection = finish == null ? Selection.FINISH : Selection.NONE;
        } else if (selection == Selection.FINISH) {
            finish = point;
            selection = start == null ? Selection.START : Selection.NONE;
        } else if (waypoints.size() < MAX_WAYPOINTS) {
            waypoints.add(point);
        }
        if (focus) map.focus(point);
        ensureBlankOrigin(point);
        routeEdited();
    }

    private void coordinateDialog(Selection target) {
        Geo.Point saved = target == Selection.START ? start : finish;
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setTextSize(16);
        field.setHint("例如 30.518000, 114.413000");
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        if (saved != null) field.setText(coordinates(saved));
        LinearLayout wrapper = Ui.column(this);
        wrapper.setPadding(dp(22), dp(12), dp(22), dp(5));
        addBelow(wrapper, Ui.text(this, "按「纬度, 经度」输入 WGS84 坐标。", 13, Ui.MUTED), 0);
        addBelow(wrapper, field, 8);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(target == Selection.START ? "输入起点坐标" : "输入终点坐标")
                .setView(wrapper).setNegativeButton("取消", null).setPositiveButton("确认", null).create();
        dialog.setOnShowListener(v -> {
            field.requestFocus();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
                try {
                    String[] pieces = field.getText().toString().trim().split("[,，\\s]+");
                    if (pieces.length != 2) throw new IllegalArgumentException();
                    Geo.Point point = new Geo.Point(Double.parseDouble(pieces[0]), Double.parseDouble(pieces[1]));
                    selection = target;
                    choosePoint(point, true);
                    dialog.dismiss();
                } catch (IllegalArgumentException error) {
                    field.setError("请输入有效的纬度和经度");
                }
            });
        });
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        dialog.show();
    }

    private String coordinates(Geo.Point point) {
        return String.format(Locale.ROOT, "%.6f, %.6f", point.latitude, point.longitude);
    }

    private void resetRoute() {
        start = null;
        finish = null;
        waypoints.clear();
        selection = Selection.START;
        routeEdited();
        map.overview();
    }

    private void routeEdited() {
        ignoredStatus = PlaybackService.status();
        previewDone = false;
        recomputeRoute();
        saveFields();
    }

    private void recomputeRoute() {
        route = null;
        directLegs = 0;
        if (start != null && finish != null) {
            ArrayList<Geo.Point> controls = new ArrayList<>(waypoints.size() + 2);
            controls.add(start);
            controls.addAll(waypoints);
            controls.add(finish);
            ArrayList<Geo.Point> drawn = new ArrayList<>();
            drawn.add(start);
            for (int i = 1; i < controls.size(); i++) {
                Geo.Point from = controls.get(i - 1);
                Geo.Point to = controls.get(i);
                if (Geo.distance(from, to) < 0.01) continue;
                List<Geo.Point> leg = alongRoads && offlineMap != null ? offlineMap.walkingLeg(from, to) : null;
                if (leg == null || leg.isEmpty()) {
                    drawn.add(to);
                    directLegs++;
                } else {
                    drawn.addAll(leg.subList(1, leg.size()));
                }
            }
            if (drawn.size() > 1) route = new Geo.Route(drawn);
        }
        displayedRoute = null;
        text(startLabel, start == null ? "起点   地图选点" : "起点   " + coordinates(start));
        text(finishLabel, finish == null ? "终点   地图选点" : "终点   " + coordinates(finish));
        text(routing, alongRoads ? "沿道路" : "点间直连");
        if (route == null) {
            text(routeDescription, start != null && finish != null
                    ? "起点与终点相同。可加途经点形成环线。" : "可在地图选点，也可输入坐标。");
        } else if (mapLoading && alongRoads) {
            text(routeDescription, "正在载入校园道路…");
        } else if (offlineMap == null && alongRoads) {
            text(routeDescription, "空白地图：未加载道路，按选点顺序直连。" + waypointCount());
        } else if (alongRoads && directLegs > 0) {
            text(routeDescription, (directLegs == waypoints.size() + 1 ? "未找到道路，使用点间直连。"
                    : directLegs + " 段未找到道路，使用直连。") + waypointCount());
        } else {
            text(routeDescription, (alongRoads ? "沿校园道路规划。" : "按选点顺序直连。") + waypointCount());
        }
        map.setRoute(start, finish, waypoints, route);
        syncMetrics();
        updatePlayback();
    }

    private String waypointCount() {
        return waypoints.isEmpty() ? "" : " 途经点 " + waypoints.size() + " 个。";
    }

    private double speedMetersPerSecond() {
        try {
            double value = Double.parseDouble(speed.getText().toString().trim().replace(',', '.')) / 3.6;
            return PlaybackService.validSpeed(value) ? value : 0;
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private void syncMetrics() {
        double value = speedMetersPerSecond();
        text(distanceValue, route == null ? "—" : Ui.distance(route.meters));
        text(timeValue, route == null || value <= 0 ? "—" : Ui.duration(route.meters / value));
        text(paceValue, value <= 0 ? "—" : Ui.duration(1_000 / value));
    }

    private void previewOrStop() {
        PlaybackService.Status service = PlaybackService.status();
        if (service.phase == PlaybackService.Phase.ARRIVED) {
            openCampusApp();
            return;
        }
        if (service.active()) {
            serviceAction(PlaybackService.STOP);
            return;
        }
        if (previewing) {
            previewing = false;
            previewDone = false;
        } else if (route != null && speedMetersPerSecond() > 0) {
            dismissKeyboard();
            ignoredStatus = service;
            previewDone = false;
            previewStart = SystemClock.elapsedRealtime();
            previewSpeed = speedMetersPerSecond();
            previewRoute = route;
            previewing = true;
            map.overview();
        }
        updatePlayback();
    }

    private void primaryAction() {
        PlaybackService.Status service = PlaybackService.status();
        if (service.phase == PlaybackService.Phase.READY) {
            serviceAction(PlaybackService.MOVE);
        } else if (service.phase == PlaybackService.Phase.ARRIVED) {
            serviceAction(PlaybackService.STOP);
        } else if (!controlsLocked() && route != null && speedMetersPerSecond() > 0) {
            dismissKeyboard();
            prepareOutput(true);
        }
    }

    private void prepareOutput(boolean offerNotifications) {
        if (route == null || speedMetersPerSecond() <= 0 || PlaybackService.status().active()) return;
        AppOpsManager ops = getSystemService(AppOpsManager.class);
        if (ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, getApplicationInfo().uid, getPackageName())
                != AppOpsManager.MODE_ALLOWED) {
            new AlertDialog.Builder(this).setTitle("选择模拟定位应用")
                    .setMessage("在手机的开发者选项中，找到「选择模拟位置信息应用」，选中「校园路线」。设置好后，回来再点「准备系统定位」。")
                    .setNegativeButton("关闭", null)
                    .setPositiveButton("打开开发者选项", (dialog, which) ->
                            launch(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                                    "请在手机设置中开启开发者选项"))
                    .show();
            return;
        }
        LocationManager manager = getSystemService(LocationManager.class);
        boolean enabled = Build.VERSION.SDK_INT >= 28 ? manager.isLocationEnabled()
                : manager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        if (!enabled) {
            new AlertDialog.Builder(this).setTitle("开启手机定位")
                    .setMessage("系统定位输出需要先打开手机的位置服务。")
                    .setNegativeButton("关闭", null).setPositiveButton("定位设置", (dialog, which) ->
                            launch(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), "请在手机设置中开启定位"))
                    .show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, ASK_LOCATION);
            return;
        }
        if (offerNotifications && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, ASK_NOTIFICATIONS);
            return;
        }
        try {
            saveFields();
            priorStatus = PlaybackService.status();
            ignoredStatus = priorStatus;
            previewDone = false;
            preparingAt = SystemClock.elapsedRealtime();
            startForegroundService(PlaybackService.prepare(this, route, speedMetersPerSecond()));
            updatePlayback();
        } catch (RuntimeException error) {
            preparingAt = 0;
            message("无法启动系统定位，请回到应用后重试。");
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == ASK_LOCATION) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                ui.post(() -> prepareOutput(true));
            } else {
                message("请在应用权限中允许定位，再准备系统定位。");
            }
        } else if (request == ASK_NOTIFICATIONS) {
            ui.post(() -> prepareOutput(false));
        }
    }

    private void updatePlayback() {
        if (primary == null) return;
        PlaybackService.Status service = PlaybackService.status();
        long now = SystemClock.elapsedRealtime();
        if (preparingAt != 0 && (service != priorStatus || now - preparingAt > 5_000)) {
            if (service == priorStatus) message("系统定位启动超时，可点按重试。");
            preparingAt = 0;
        }
        Geo.Point position = null;
        Geo.Route visibleRoute = service.active() ? service.route : previewing ? previewRoute : route;
        String feedback = "";
        String action = "准备系统定位";
        String secondary = "预览";
        if (previewing) {
            double traveled = Math.min(previewRoute.meters, (now - previewStart) * previewSpeed / 1_000);
            position = previewRoute.at(traveled).point;
            feedback = "预览  " + Ui.distance(traveled) + " / " + Ui.distance(previewRoute.meters);
            secondary = "停止预览";
            if (traveled >= previewRoute.meters) {
                previewing = false;
                previewDone = true;
                secondary = "预览";
            }
        }
        if (previewDone && !service.active()) {
            position = finish;
            feedback = "预览结束，已到终点。";
        }
        if (preparingAt != 0) {
            action = "正在准备…";
        } else if (service != ignoredStatus && service.phase != PlaybackService.Phase.IDLE) {
            position = service.point;
            switch (service.phase) {
                case READY:
                    action = "开始移动";
                    secondary = "停止输出";
                    boolean notification = getSystemService(NotificationManager.class).areNotificationsEnabled();
                    feedback = notification ? "起点定位已就绪。切换应用后，可从通知栏开始移动。"
                            : "起点定位已就绪。通知权限关闭，可在这里点「开始移动」。";
                    break;
                case MOVING:
                    action = "正在移动…";
                    secondary = "停止输出";
                    feedback = "定位输出  " + Ui.distance(service.distance) + " / " + Ui.distance(service.route.meters);
                    break;
                case ARRIVED:
                    action = "结束输出";
                    secondary = "华中大体育";
                    long seconds = Math.max(0, (service.endDeadline - now + 999) / 1_000);
                    feedback = "已到终点，" + seconds + " 秒后恢复系统定位。";
                    break;
                case FINISHED:
                    feedback = "已到达终点，系统定位已恢复。";
                    break;
                case STOPPED:
                    feedback = "输出已停止，系统定位已恢复。";
                    break;
                case FAILED:
                    feedback = service.error;
                    break;
                default:
                    break;
            }
        }
        if (visibleRoute != displayedRoute) {
            displayedRoute = visibleRoute;
            map.setRoute(start, finish, waypoints, visibleRoute);
        }
        map.setPosition(position);
        text(status, feedback);
        status.setVisibility(feedback.isEmpty() ? View.GONE : View.VISIBLE);
        text(headerAction, service.active() ? "打开华中大体育" : "使用说明");
        text(primary, action);
        text(preview, secondary);
        boolean valid = route != null && speedMetersPerSecond() > 0;
        boolean canStart = (valid && !controlsLocked()) || service.phase == PlaybackService.Phase.READY
                || service.phase == PlaybackService.Phase.ARRIVED;
        if (primary.isEnabled() != canStart || primary.getTag() == null) {
            primary.setTag(Boolean.TRUE);
            primary.setEnabled(canStart);
            Ui.styleButton(primary, canStart ? Ui.ACCENT : Ui.PANEL, canStart ? Ui.DARK_INK : Ui.MUTED,
                    canStart ? 0 : Ui.LINE, 13);
        }
        boolean edit = !controlsLocked();
        boolean both = start != null && finish != null;
        enable(preview, (valid && preparingAt == 0) || service.active() || previewing);
        for (View view : new View[]{startLabel, finishLabel, startCoordinates, finishCoordinates, routing, speed}) {
            enable(view, edit);
        }
        enable(reset, edit && (start != null || finish != null));
        enable(addWaypoint, edit && both && (selection == Selection.WAYPOINT || waypoints.size() < MAX_WAYPOINTS));
        enable(undoWaypoint, edit && !waypoints.isEmpty());
        updateSelection();
    }

    private void enable(View view, boolean ready) {
        view.setEnabled(ready);
        view.setAlpha(ready ? 1f : 0.52f);
    }

    private void serviceAction(String command) {
        try {
            startService(PlaybackService.command(this, command));
        } catch (RuntimeException error) {
            message("无法控制系统定位，请从通知栏操作。");
        }
    }

    private void dismissKeyboard() {
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(speed.getWindowToken(), 0);
        speed.clearFocus();
    }

    private void launch(Intent intent, String problem) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException error) {
            message(problem);
        }
    }

    private void openCampusApp() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(CAMPUS_APP);
        if (intent == null) {
            message("未发现华中大体育，可从手机桌面手动打开。");
            return;
        }
        launch(intent, "未能打开华中大体育，可从手机桌面手动打开。");
    }

    private void message(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    // ---- 自定义地图导入 ----

    @SuppressWarnings("deprecation")
    private void pickMapFile() {
        if (controlsLocked()) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, PICK_MAP);
        } catch (ActivityNotFoundException error) {
            message("未找到文件选择器，可安装系统文件管理应用。");
        }
    }

    @SuppressWarnings("deprecation")
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_MAP || result != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        new Thread(() -> {
            try {
                String content = readUri(uri);
                CampusMap.parse(content); // 校验格式，失败抛异常
                File file = new File(getFilesDir(), MAP_FILE);
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(content.getBytes(StandardCharsets.UTF_8));
                }
                String name = displayName(uri);
                ui.post(() -> {
                    if (isDestroyed()) return;
                    prefs().edit().putString("mapMode", "auto")
                            .putString("mapName", name == null ? "" : name).apply();
                    message("已导入地图" + (name == null || name.isEmpty() ? "" : "：" + name));
                    loadMap();
                });
            } catch (Exception error) {
                Log.w("CampusRoute", "Could not import map", error);
                ui.post(() -> {
                    if (!isDestroyed()) message("地图格式错误，无法导入。支持 GeoJSON 或 campus-map.json 格式。");
                });
            }
        }, "import-map").start();
    }

    private String readText(File file) throws IOException {
        try (InputStream stream = new FileInputStream(file)) {
            return readUri(stream);
        }
    }

    private String readUri(Uri uri) throws IOException {
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            if (stream == null) throw new IOException("cannot open");
            return readUri(stream);
        }
    }

    private String readUri(InputStream stream) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8_192];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            bytes.write(buffer, 0, read);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private String displayName(Uri uri) {
        String name = null;
        try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    name = cursor.getString(index);
                }
            }
        } catch (Exception error) {
            Log.i("CampusRoute", "Could not resolve display name", error);
        }
        if (name == null) {
            name = uri.getLastPathSegment();
        }
        return name;
    }

    private void showHelp() {
        new AlertDialog.Builder(this).setTitle("校园路线使用说明")
                .setMessage("在地图上点出起点和终点，输入 km/h 速度，再预览路线与用时。"
                        + "要经过指定位置，可按顺序添加途经点。\n\n"
                        + "地图：内置华中科技大学离线地图。点「导入地图」可选择 GeoJSON 或"
                        + " campus-map.json 格式的其它校园地图；点「空白地图」可切换无底图模式，"
                        + "直接选点画圈或画线。\n\n"
                        + "在 Android 开发者选项中，把「校园路线」选为模拟位置信息应用。"
                        + "点「准备系统定位」后，手机定位停在起点。切换应用后，可从通知栏点「开始移动」。"
                        + "到达终点后保留定位 60 秒。\n\n"
                        + "系统会标记模拟定位。华中大体育中的轨迹显示和评分次数由其自身判定。")
                .setPositiveButton("知道了", null).show();
    }
}
