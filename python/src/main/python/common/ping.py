import json

def ping(msg: str = "pong") -> str:
    """Smoke test function for verifying Chaquopy bridge end-to-end."""
    return json.dumps({
        "status": "ok",
        "pong": msg,
        "runtime": "chaquopy_python_3.11"
    })
