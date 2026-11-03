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
 'com/tactical/neoforge/client/ui/hud/HudLayout.class',
 'com/tactical/neoforge/client/ui/layout/Scale.class',
 'com/tactical/neoforge/battle/TacticalAttributes.class',
 'com/tactical/neoforge/client/BattleTool.class',
 'com/tactical/neoforge/client/PlayerStepAnimation.class',
 'com/tactical/neoforge/client/UiZoom.class',
 'com/tactical/neoforge/client/CssScale.class',
 'com/tactical/neoforge/client/ui/screen/NativeTacticalScreen.class',
 'com/tactical/neoforge/client/ui/screen/TacticalSurface.class',
 'com/tactical/neoforge/client/ui/panel/NativePanelScreen.class',
 'com/tactical/neoforge/client/ui/command/BattleMenuScreen.class',
 'com/tactical/neoforge/client/ui/aim/BattleAimScreen.class',
 'com/tactical/neoforge/client/ui/facing/BattleFacingScreen.class',
 'com/tactical/neoforge/client/ui/skill/SkillMenuScreen.class',
 'com/tactical/neoforge/client/ui/hud/NativeHud.class',
 'com/tactical/neoforge/client/ui/hud/JournalViewport.class',
 'com/tactical/neoforge/client/ui/platform/Draw.class',
 'tactical.camera.mixins.json',
]
with ZipFile(jar) as archive:
    names = set(archive.namelist())
    for path in required:
        assert path in names, f'missing {path}'
    configs = ('tactical.camera.mixins.json', 'tactical.effects.mixins.json', 'tactical.creeper.mixins.json',
               'tactical.dungeons_input.mixins.json', 'tactical.server.mixins.json')
    pack = json.loads(archive.read('pack.mcmeta'))
    assert pack['pack']['pack_format'] == 34
    # The bundled UI framework and its page sources are gone: no page, no font, no engine asset.
    assert not any(name.startswith('assets/apricityui/') for name in names), \
        'the retired UI dependency asset tree must not be packaged'
    assert not any(name.startswith('com/sighs/') for name in names), \
        'no class of the retired UI dependency may be packaged'
    assert 'tactical.ui.mixins.json' not in names, 'the retired mixin config must not be packaged'
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
    assert 'tactical.camera.mixins.json' in metadata and 'tactical.server.mixins.json' in metadata
    assert 'tactical.ui.mixins.json' not in metadata, 'the retired mixin config must not be declared'
    assert 'apricity' not in metadata.lower(), 'the metadata must not require the retired UI dependency'
    assert 'dungeons_iso' in metadata, 'the optional camera dependency must stay declared'
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
    # The interface draws its own shapes and text; no font file may be packaged.
    assert not any(name.lower().endswith('.ttf') for name in names), 'no font file may be packaged'
    # The shipped language files stay: every native label resolves from them.
    for lang in ('assets/tactical/lang/en_us.json', 'assets/tactical/lang/zh_cn.json'):
        assert lang in names, f'missing {lang}'
    assert not any('agenthelper' in name.lower() for name in names)
print(f'PASS packaging: {jar.name} ({jar.stat().st_size} bytes)')
print(f'SHA256 {sha256(jar.read_bytes()).hexdigest()}')
print(f'NESTED {expression_lib.NESTED_PATH} SHA256 {nested_sha} '
      f'(official {expression_lib.OFFICIAL_COMMIT[:10]})')
