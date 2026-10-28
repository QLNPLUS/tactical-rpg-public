"""Single source of truth for the bundled YiRanExpressionLib artifact.

The nested JAR shipped in the product must be the *official* JitPack `universal`
build of upstream commit `60584101e698d1e3f79d327b18982dec7ddb5465`, byte for
byte. A normalized Maven identity (`com.github.yiran1457:YiRanExpressionLib:1.0.1`)
is kept because that is the identity this project has always published, but
identity/coordinates alone never prove which binary is inside, so the archive
bytes are pinned here and checked by both release tools.

That identity is *not* aligned with the current Advanced Leveling fork: measured
in an isolated dedicated server, `AdvancedLeveling-1.21.1-1.0.1-fix` requests
`[1.21.1-NeoForge-1.0.0,)` and ships a library whose descriptor says
`version="1.0.0"` / `license="AGPL v3.0"`, so co-loading both JARs fails with
JarJar `VERSION_RESOLUTION_FAILED`. Do not reintroduce an "aligned with Advanced
Leveling" claim here; see libs/maven/README.md and docs/license-audit.md.

This module only reports what the bytes are. It is not a license conclusion: the
library stays under its own LGPL v3.0 terms (see NOTICE.md, docs/license-audit.md).
"""
import io
import json
from hashlib import sha256
from zipfile import ZipFile

import tomllib

GROUP = 'com.github.yiran1457'
ARTIFACT = 'YiRanExpressionLib'
VERSION = '1.0.1'
RANGE = '[1.0.1]'

# Normalized local Maven layout the build resolves from (see libs/maven/README.md).
ARTIFACT_PATH = f'libs/maven/com/github/yiran1457/YiRanExpressionLib/1.0.1/YiRanExpressionLib-1.0.1.jar'
# JarJar nests the library under this exact path; the metadata entry must agree.
NESTED_PATH = f'META-INF/jarjar/YiRanExpressionLib-1.0.1.jar'
METADATA_PATH = 'META-INF/jarjar/metadata.json'

# Official JitPack artifact for the pinned upstream commit:
# https://jitpack.io/com/github/yiran1457/YiRanExpressionLib/60584101e6/YiRanExpressionLib-60584101e6-universal.jar
OFFICIAL_COMMIT = '60584101e698d1e3f79d327b18982dec7ddb5465'
OFFICIAL_FILE = 'YiRanExpressionLib-60584101e6-universal.jar'
OFFICIAL_BYTES = 62897
OFFICIAL_SHA256 = 'd797d9eaf22923b5f0d0d152b583aef3d60600bf349aa481a89ecd159255106a'

# Facts inside those bytes: mod id/version/license of both descriptors, Java 17 classes.
MOD_ID = 'yiranexpressionlib'
MOD_LICENSE = 'LGPL v3.0'
DESCRIPTORS = ('META-INF/neoforge.mods.toml', 'META-INF/mods.toml')


def local_artifact(root):
    return root / ARTIFACT_PATH


def digest(data):
    return sha256(data).hexdigest()


def check_official_bytes(data, where='nested YiRanExpressionLib'):
    """Reject anything that is not the official upstream build, byte for byte."""
    if len(data) != OFFICIAL_BYTES or digest(data) != OFFICIAL_SHA256:
        raise ValueError(f'{where} is not the official {OFFICIAL_FILE} '
                         f'({len(data)} bytes, sha256 {digest(data)}); expected '
                         f'{OFFICIAL_BYTES} bytes, sha256 {OFFICIAL_SHA256}')
    return digest(data)


def check_descriptors(data):
    """The pinned bytes must still declare the expected library identity."""
    with ZipFile(io.BytesIO(data)) as library:
        names = set(library.namelist())
        for descriptor in DESCRIPTORS:
            if descriptor not in names:
                raise ValueError(f'library is missing {descriptor}')
            metadata = tomllib.loads(library.read(descriptor).decode('utf-8'))
            mods = [item for item in metadata.get('mods', []) if item.get('modId') == MOD_ID]
            if len(mods) != 1 or mods[0].get('version') != VERSION:
                raise ValueError(f'{descriptor} does not declare {MOD_ID} {VERSION}')
            if metadata.get('license') != MOD_LICENSE:
                raise ValueError(f'{descriptor} does not declare {MOD_LICENSE}')
    return names


def check_local_artifact(root):
    """The resolved Maven artifact on disk is the official build."""
    path = local_artifact(root)
    if not path.is_file():
        raise ValueError(f'missing bundled library artifact {ARTIFACT_PATH}')
    data = path.read_bytes()
    check_official_bytes(data, f'{ARTIFACT_PATH}')
    check_descriptors(data)
    return digest(data)


def check_jars_metadata(metadata):
    """Exactly one JarJar entry, with the pinned identity, range and path."""
    jars = metadata.get('jars')
    if not isinstance(jars, list) or len(jars) != 1:
        raise ValueError('JarJar metadata must contain exactly one nested library entry')
    entry = jars[0]
    identifier = entry.get('identifier') or {}
    version = entry.get('version') or {}
    expected = {'group': GROUP, 'artifact': ARTIFACT}
    if {key: identifier.get(key) for key in expected} != expected:
        raise ValueError(f'JarJar identity must be {GROUP}:{ARTIFACT}')
    if version.get('range') != RANGE or version.get('artifactVersion') != VERSION:
        raise ValueError(f'JarJar version must be range {RANGE} / {VERSION}')
    if entry.get('path') != NESTED_PATH:
        raise ValueError(f'JarJar path must be {NESTED_PATH}')
    return entry


def check_nested(archive, root, names=None):
    """The product carries exactly one nested library, and it is the official build.

    The listing is checked as raw entries, not as a set: a ZIP may legally contain two
    entries with the same name, and ``set()`` would collapse them while ``read()``
    silently returns the last one.
    """
    listing = archive.namelist()
    names = set(listing) if names is None else names
    for single in (METADATA_PATH, NESTED_PATH):
        count = listing.count(single)
        if count != 1:
            raise ValueError(f'{single} must appear exactly once in the archive, found {count}')
    if METADATA_PATH not in names:
        raise ValueError(f'missing {METADATA_PATH}')
    check_jars_metadata(json.loads(archive.read(METADATA_PATH)))
    nested = sorted(name for name in names
                    if name.startswith('META-INF/jarjar/') and name.endswith('.jar'))
    if nested != [NESTED_PATH]:
        raise ValueError(f'exactly one nested JAR {NESTED_PATH} is allowed, found {nested}')
    data = archive.read(NESTED_PATH)
    check_official_bytes(data)
    check_descriptors(data)
    local = local_artifact(root)
    if not local.is_file() or local.read_bytes() != data:
        raise ValueError(f'nested library differs from {ARTIFACT_PATH}; '
                         'rebuild after refreshing the bundled artifact')
    return digest(data)
