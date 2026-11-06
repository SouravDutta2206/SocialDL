"""Twitter/X bridge package (Phase 6).

Hybrid Twitter/X extraction used through Chaquopy's JSON-only boundary:

- ``twitter_bridge.extract.extract``

Engine selection happens inside the bridge: a signed-in cookie jar routes to
gallery-dl; without a jar the public Syndication CDN is used anonymously. Any
gallery-dl failure transparently falls back to syndication.
"""
