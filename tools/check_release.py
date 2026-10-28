from pathlib import Path
from zipfile import ZipFile
from hashlib import sha256
import json
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import expression_lib

root = Path(__file__).resolve().parent.parent
properties = (root / 'gradle.properties').read_text(encoding='utf-8')
version = re.search(r'^mod_version=(.+)$', properties, re.M).group(1).strip()
jar = root / f'neoforge-1.21.1/build/libs/tactical-neoforge-1.21.1-{version}.jar'
required = [
 'com/tactical/core/combat/PositionalDamage.class',
 'com/tactical/neoforge/battle/TacticalDamageMultipliers.class',
 'com/tactical/neoforge/battle/TacticalDamageEvent.class',
 'com/tactical/neoforge/battle/ArrowImpactGeometry.class',
 'com/tactical/neoforge/battle/PositionalCombat.class',
 'com/tactical/neoforge/battle/NativeAttackReceipt.class',
 'com/tactical/neoforge/client/HealthBarRenderer.class',
 'com/tactical/neoforge/client/AttackRangeRefresh.class',
 'com/tactical/core/grid/GridTrajectory.class',
 'com/tactical/neoforge/battle/BattleProjectiles.class',
 'com/tactical/neoforge/battle/BallisticShot.class',
 'com/tactical/neoforge/battle/ai/SkeletonTacticalAi.class',
 'com/tactical/neoforge/mixin/BattleFoodDataMixin.class',
 'com/tactical/neoforge/battle/BattleBurning.class',
 'com/tactical/neoforge/battle/BattleDeployment.class',
 'com/tactical/neoforge/battle/BattleToolUse.class',
 'com/tactical/neoforge/battle/AerialMovement.class',
 'com/tactical/neoforge/battle/ai/PhantomTacticalAi.class',
 'com/tactical/neoforge/battle/FleeProtection.class',
 'com/tactical/neoforge/client/TurnChargeRailLayout.class',
 'com/tactical/neoforge/client/BattleScreenInput.class',
 'com/tactical/neoforge/client/BattleEntityLocator.class',
 'com/tactical/neoforge/client/TurnChargeHud.class',
 'com/tactical/neoforge/client/JournalUiLayout.class',
 'com/tactical/neoforge/battle/TacticalAttributes.class',
 'com/tactical/neoforge/client/BattleTool.class',
 'com/tactical/neoforge/client/WindowEntrance.class',
 'com/tactical/neoforge/client/PlayerStepAnimation.class',
 'com/tactical/neoforge/mixin/TacticalAuiCursorMixin.class',
 'tactical.camera.mixins.json', 'tactical.ui.mixins.json',
]
with ZipFile(jar) as archive:
    names = set(archive.namelist())
    for path in required:
        assert path in names, f'missing {path}'
    configs = ('tactical.camera.mixins.json', 'tactical.ui.mixins.json', 'tactical.effects.mixins.json', 'tactical.creeper.mixins.json', 'tactical.dungeons_input.mixins.json')
    pack = json.loads(archive.read('pack.mcmeta'))
    assert pack['pack']['pack_format'] == 34
    for page in ('screens/battle_move_choice.html', 'overlays/battle_log.html'):
        assert 'assets/apricityui/apricity/tactical/' + page in names
    for config in configs:
        mixins = json.loads(archive.read(config))
        if mixins.get('plugin'):
            assert mixins['plugin'].replace('.', '/') + '.class' in names
        if mixins.get('refmap'):
            assert mixins['refmap'] in names, f'missing refmap for {config}'
        for cls in mixins.get('client', []) + mixins.get('mixins', []):
            path = mixins['package'].replace('.', '/') + '/' + cls.replace('.', '/') + '.class'
            assert path in names, f'missing mixin class {path}'
    metadata = archive.read('META-INF/neoforge.mods.toml').decode()
    assert f'version = "{version}"' in metadata
    assert 'tactical.camera.mixins.json' in metadata and 'tactical.ui.mixins.json' in metadata
    assert 'license = "LGPL-3.0-only"' in metadata, 'mod metadata must declare LGPL-3.0-only'
    licence = archive.read('META-INF/LICENSE')
    assert licence == (root / 'licenses/YiRanExpressionLib-LICENSE.txt').read_bytes(), \
        'META-INF/LICENSE must carry the complete LGPL v3 text'
    for entry in ('META-INF/NOTICE.md',
                  'META-INF/licenses/yiran-expression-lib/YiRanExpressionLib-LICENSE.txt',
                  'META-INF/licenses/yiran-expression-lib/GPL-3.0.txt'):
        assert entry in names, f'missing {entry}'
    assert archive.read('META-INF/licenses/yiran-expression-lib/YiRanExpressionLib-LICENSE.txt') == licence
    assert archive.read('META-INF/licenses/yiran-expression-lib/GPL-3.0.txt') == \
        (root / 'licenses/GPL-3.0.txt').read_bytes()
    # Exactly one nested library entry; its bytes must be the official upstream build and
    # agree with the resolved Maven artifact, under the pinned JarJar identity/range/path.
    expression_lib.check_local_artifact(root)
    nested_sha = expression_lib.check_nested(archive, root, names)
    # The UI renders with the vanilla font; the leftover third-party font must not ship.
    assert not any(name.lower().endswith('.ttf') for name in names), 'no font file may be packaged'
    assert 'assets/apricityui/apricity/tactical/lxgw3500.ttf' not in names
    page = archive.read('assets/apricityui/apricity/tactical/screens/battle_inventory.html').decode()
    assert len(re.findall(r'<slot\s', page)) == 36
    assert 'slot > item { left:0; top:0; width:100%; height:100%' in page
    prefix = 'assets/apricityui/apricity/tactical/'
    # Hint overlay: no journal chrome. tools/apply_journal_theme.py does not theme it either.
    non_journal_pages = {'overlays/nearby_hint.html'}
    for file in sorted((root / 'ui').rglob('*')):
        if not file.is_file() or file.suffix not in ('.html', '.png', '.css', '.json'):
            continue
        path = prefix + file.relative_to(root / 'ui').as_posix()
        assert path in names, f'missing journal asset {path}'
        assert archive.read(path) == file.read_bytes(), f'stale journal asset {path}'
        if file.suffix == '.html':
            html = archive.read(path).decode('utf-8')
            # Every page renders with the vanilla font; only the journal pages carry the theme hook.
            assert 'name="aui-font-mode" content="mc"' in html, path
            assert 'html { font-size:14px; }' in html, f'missing AUI em fallback {path}'
            if file.relative_to(root / 'ui').as_posix() in non_journal_pages:
                assert 'data-tactical-theme' not in html, f'{path} must stay theme-free'
            else:
                assert 'data-tactical-theme="gilded-journal"' in html, path
    assert not any('agenthelper' in name.lower() for name in names)
print(f'PASS packaging: {jar.name} ({jar.stat().st_size} bytes)')
print(f'SHA256 {sha256(jar.read_bytes()).hexdigest()}')
print(f'NESTED {expression_lib.NESTED_PATH} SHA256 {nested_sha} '
      f'(official {expression_lib.OFFICIAL_COMMIT[:10]})')
