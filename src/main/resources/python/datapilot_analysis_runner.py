import ast
import json
import math
import sys
from datetime import date, datetime, time
from decimal import Decimal

try:
    import numpy as np
    import pandas as pd
    from scipy import stats
except ModuleNotFoundError as exc:
    print(f"PYTHON_DEPENDENCY_MISSING: {exc}", file=sys.stderr)
    raise


BLOCKED_CALLS = {
    "breakpoint", "compile", "eval", "exec", "getattr", "globals", "help",
    "input", "locals", "memoryview", "open", "setattr", "vars", "__import__",
}


class CodePolicy(ast.NodeVisitor):
    def reject(self, reason):
        raise ValueError(f"PYTHON_CODE_REJECTED: {reason}")

    def visit_Import(self, node):
        self.reject("不允许 import")

    def visit_ImportFrom(self, node):
        self.reject("不允许 from import")

    def visit_Global(self, node):
        self.reject("不允许 global")

    def visit_Nonlocal(self, node):
        self.reject("不允许 nonlocal")

    def visit_ClassDef(self, node):
        self.reject("不允许定义 class")

    def visit_AsyncFunctionDef(self, node):
        self.reject("不允许异步函数")

    def visit_Await(self, node):
        self.reject("不允许 await")

    def visit_Yield(self, node):
        self.reject("不允许 yield")

    def visit_YieldFrom(self, node):
        self.reject("不允许 yield from")

    def visit_Attribute(self, node):
        if node.attr.startswith("__"):
            self.reject("不允许访问双下划线属性")
        self.generic_visit(node)

    def visit_Name(self, node):
        if node.id.startswith("__"):
            self.reject("不允许访问双下划线名称")
        self.generic_visit(node)

    def visit_Call(self, node):
        if isinstance(node.func, ast.Name) and node.func.id in BLOCKED_CALLS:
            self.reject(f"不允许调用 {node.func.id}")
        if isinstance(node.func, ast.Attribute) and node.func.attr in {
            "read_csv", "read_excel", "read_json", "read_parquet", "read_pickle",
            "to_csv", "to_excel", "to_json", "to_parquet", "to_pickle",
        }:
            self.reject(f"不允许文件读写方法 {node.func.attr}")
        self.generic_visit(node)


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
                row[name] = Decimal(value)


def normalize(value, warnings, path="result"):
    if isinstance(value, np.generic):
        return normalize(value.item(), warnings, path)
    if isinstance(value, (pd.Timestamp, datetime, date, time)):
        return value.isoformat()
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, float) and not math.isfinite(value):
        warnings.append(f"{path} 的 NaN 或 Infinity 已转换为 null")
        return None
    if value is pd.NA or value is pd.NaT:
        return None
    if isinstance(value, dict):
        return {str(key): normalize(item, warnings, f"{path}.{key}") for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [normalize(item, warnings, f"{path}[{index}]") for index, item in enumerate(value)]
    if isinstance(value, np.ndarray):
        return normalize(value.tolist(), warnings, path)
    if isinstance(value, pd.Series):
        return normalize(value.to_dict(), warnings, path)
    if isinstance(value, pd.DataFrame):
        return normalize(value.to_dict(orient="records"), warnings, path)
    if value is None or isinstance(value, (str, int, bool, float)):
        return value
    raise TypeError(f"PYTHON_RESULT_INVALID: 不支持的结果类型 {type(value).__name__}")


def main():
    request = json.load(sys.stdin)
    dataset = request["dataset"]
    code = request["code"]
    tree = ast.parse(code, mode="exec")
    CodePolicy().visit(tree)
    convert_decimals(dataset)
    df = pd.DataFrame(dataset.get("rows", []), columns=[c["name"] for c in dataset.get("columns", [])])
    safe_builtins = {
        "abs": abs, "all": all, "any": any, "bool": bool, "dict": dict,
        "enumerate": enumerate, "float": float, "int": int, "len": len,
        "list": list, "max": max, "min": min, "range": range, "round": round,
        "set": set, "sorted": sorted, "str": str, "sum": sum, "tuple": tuple,
        "zip": zip,
    }
    namespace = {
        "__builtins__": safe_builtins,
        "df": df, "pd": pd, "np": np, "stats": stats, "Decimal": Decimal,
    }
    exec(compile(tree, "<datapilot-analysis>", "exec"), namespace, namespace)
    result = namespace.get("result")
    if result is None:
        raise ValueError("PYTHON_RESULT_MISSING: 缺少 result")
    if not isinstance(result, dict):
        raise TypeError("PYTHON_RESULT_INVALID: result 必须是对象")
    warnings = []
    normalized = normalize(result, warnings)
    json.dump(
        {"result": normalized, "warnings": warnings},
        sys.stdout,
        ensure_ascii=False,
        allow_nan=False,
        separators=(",", ":"),
    )


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
