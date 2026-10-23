import json
from typing import Any


def success_envelope(data: Any) -> str:
    """Wraps data in standard JSON success envelope."""
    return json.dumps({
        "status": "success",
        "data": data
    })


def error_envelope(error_type: str, detail: str) -> str:
    """Wraps error in standard JSON error envelope for Kotlin-side dispatch."""
    return json.dumps({
        "error": error_type,
        "detail": detail
    })
