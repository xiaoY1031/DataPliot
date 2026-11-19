import json
import math
import sys
from decimal import Decimal

try:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib import font_manager
    import pandas as pd
except ModuleNotFoundError as exc:
    print(f"CHART_DEPENDENCY_MISSING: {exc}", file=sys.stderr)
    raise


FONT_CANDIDATES = [
    "Microsoft YaHei", "SimHei", "Noto Sans CJK SC",
    "Arial Unicode MS", "DejaVu Sans",
]
COLORS = [
    "#4E79A7", "#F28E2B", "#E15759", "#76B7B2",
    "#59A14F", "#EDC948", "#B07AA1", "#FF9DA7",
    "#9C755F", "#BAB0AC", "#2F5597", "#70AD47",
]


def convert_decimals(dataset):
    decimal_columns = {
        column["name"]
        for column in dataset.get("columns", [])
        if column.get("encoding") == "DECIMAL_STRING"
    }
    for row in dataset.get("rows", []):
        for name in decimal_columns:
            value = row.get(name)
            if value is not None:
                row[name] = float(Decimal(value))


def configure_font(warnings):
    available = {font.name for font in font_manager.fontManager.ttflist}
    selected = next((name for name in FONT_CANDIDATES if name in available), None)
    if selected:
        plt.rcParams["font.sans-serif"] = [selected]
    else:
        warnings.append("未找到推荐中文字体，中文字符可能无法完整显示")
    plt.rcParams["axes.unicode_minus"] = False
    plt.rcParams["text.usetex"] = False


def label(value):
    if value is None:
        return ""
    return str(value)


def style_axes(ax, chart_type):
    grid_axis = "x" if chart_type == "horizontal_bar" else "y"
    ax.grid(axis=grid_axis, color="#D9D9D9", linewidth=0.7, alpha=0.65)
    ax.set_axisbelow(True)
    ax.spines["top"].set_visible(False)
    ax.spines["right"].set_visible(False)


def grouped_frame(df, x, y, series):
    if not series:
        return [(None, df)]
    return list(df.groupby(series, sort=False, dropna=False))


def render_bar(ax, df, chart, horizontal=False):
    x, y, series = chart["x"], chart["y"], chart.get("series")
    if not series:
        categories = [label(value) for value in df[x]]
        values = df[y].astype(float).tolist()
        if horizontal:
            ax.barh(categories, values, color=COLORS[0])
            ax.invert_yaxis()
        else:
            ax.bar(categories, values, color=COLORS[0])
        return
    categories = list(dict.fromkeys(label(value) for value in df[x]))
    groups = grouped_frame(df, x, y, series)
    width = 0.8 / len(groups)
    positions = list(range(len(categories)))
    for index, (name, group) in enumerate(groups):
        values_by_category = {label(row[x]): float(row[y]) for _, row in group.iterrows()}
        values = [values_by_category.get(category, math.nan) for category in categories]
        offset = (index - (len(groups) - 1) / 2) * width
        shifted = [position + offset for position in positions]
        if horizontal:
            ax.barh(shifted, values, height=width, label=label(name), color=COLORS[index % len(COLORS)])
        else:
            ax.bar(shifted, values, width=width, label=label(name), color=COLORS[index % len(COLORS)])
    if horizontal:
        ax.set_yticks(positions, categories)
        ax.invert_yaxis()
    else:
        ax.set_xticks(positions, categories)
    ax.legend(frameon=False)


def render_line(ax, df, chart):
    x, y, series = chart["x"], chart["y"], chart.get("series")
    groups = grouped_frame(df, x, y, series)
    for index, (name, group) in enumerate(groups):
        ax.plot(
            [label(value) for value in group[x]],
            group[y].astype(float),
            marker="o", linewidth=2, markersize=4,
            label=label(name) if series else None,
            color=COLORS[index % len(COLORS)],
        )
    if series:
        ax.legend(frameon=False)


def render_scatter(ax, df, chart):
    x, y, series = chart["x"], chart["y"], chart.get("series")
    groups = grouped_frame(df, x, y, series)
    for index, (name, group) in enumerate(groups):
        ax.scatter(
            group[x].astype(float), group[y].astype(float),
            alpha=0.75, s=38, label=label(name) if series else None,
            color=COLORS[index % len(COLORS)], edgecolors="white", linewidths=0.4,
        )
    if series:
        ax.legend(frameon=False)


def render_histogram(ax, df, chart):
    ax.hist(df[chart["x"]].astype(float), bins=int(chart.get("bins", 20)),
            color=COLORS[0], edgecolor="white", linewidth=0.8)


def render_box(ax, df, chart):
    x, y = chart.get("x"), chart["y"]
    if x:
        categories = list(dict.fromkeys(label(value) for value in df[x]))
        values = [df[df[x].map(label) == category][y].astype(float).tolist() for category in categories]
        box = boxplot(ax, values, categories)
    else:
        box = boxplot(ax, [df[y].astype(float).tolist()], [chart.get("yLabel") or y])
    for patch in box["boxes"]:
        patch.set_facecolor(COLORS[0])
        patch.set_alpha(0.65)


def boxplot(ax, values, labels):
    try:
        return ax.boxplot(values, tick_labels=labels, patch_artist=True, showfliers=True)
    except TypeError:
        # Matplotlib 3.8 及更早版本使用 labels 参数。
        return ax.boxplot(values, labels=labels, patch_artist=True, showfliers=True)


def main():
    request = json.load(sys.stdin)
    dataset = request["dataset"]
    chart = request["chart"]
    convert_decimals(dataset)
    df = pd.DataFrame(dataset.get("rows", []), columns=[c["name"] for c in dataset.get("columns", [])])
    required = [name for name in [chart.get("x"), chart.get("y"), chart.get("series")] if name]
    df = df.dropna(subset=required).copy()
    if df.empty:
        raise ValueError("CHART_DATA_INVALID: 没有有效数据")

    warnings = []
    configure_font(warnings)
    figure, ax = plt.subplots(figsize=(12, 7.2), dpi=100)
    chart_type = chart["chartType"]
    if chart_type == "bar":
        render_bar(ax, df, chart, horizontal=False)
    elif chart_type == "horizontal_bar":
        render_bar(ax, df, chart, horizontal=True)
    elif chart_type == "line":
        render_line(ax, df, chart)
    elif chart_type == "scatter":
        render_scatter(ax, df, chart)
    elif chart_type == "histogram":
        render_histogram(ax, df, chart)
    elif chart_type == "box":
        render_box(ax, df, chart)
    else:
        raise ValueError(f"CHART_DATA_INVALID: 不支持的图表类型 {chart_type}")

    ax.set_title(chart["title"], fontsize=16, fontweight="bold", pad=16)
    if chart_type == "horizontal_bar":
        if chart.get("xLabel"):
            ax.set_ylabel(chart["xLabel"])
        if chart.get("yLabel"):
            ax.set_xlabel(chart["yLabel"])
    else:
        if chart.get("xLabel"):
            ax.set_xlabel(chart["xLabel"])
        if chart.get("yLabel"):
            ax.set_ylabel(chart["yLabel"])
    style_axes(ax, chart_type)
    if chart_type not in {"horizontal_bar", "scatter", "histogram"}:
        figure.autofmt_xdate(rotation=30, ha="right")
    figure.tight_layout()
    figure.savefig("chart.png", format="png", dpi=100, metadata={"Software": "DataPilot"})
    plt.close(figure)
    json.dump({"warnings": warnings}, sys.stdout, ensure_ascii=False, separators=(",", ":"))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
