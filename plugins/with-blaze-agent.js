// Blaze Player (Apache-2.0). Added by Blazeinchue.
// Installs the agent interface: copies native-src/android/com/blaze/player/*.kt, registers
// AgentPackage, routes MainActivity intents to AgentCommandBus, and declares in the manifest:
//   <permission com.blaze.player.permission.AGENT protectionLevel=signature>
//   <activity-alias .AgentEntry -> .MainActivity> (open/play/cast), permission-protected
//   <activity .AgentActivity> (queue/search, startActivityForResult), permission-protected
//   <service .AgentService> (bound Messenger API), permission-protected
const fs = require('fs');
const path = require('path');
const {
  withDangerousMod,
  withMainApplication,
  withMainActivity,
  withAndroidManifest,
} = require('expo/config-plugins');

const PERMISSION = 'com.blaze.player.permission.AGENT';

function withNativeSources(config) {
  return withDangerousMod(config, [
    'android',
    async cfg => {
      const root = cfg.modRequest.projectRoot;
      const pkg = cfg.android?.package || 'com.blaze.player';
      const target = path.join(root, 'android', 'app', 'src', 'main', 'java', ...pkg.split('.'));
      const src = path.join(root, 'native-src', 'android', 'com', 'blaze', 'player');
      fs.mkdirSync(target, {recursive: true});
      for (const f of fs.readdirSync(src)) {
        if (f.endsWith('.kt')) {
          const body = fs
            .readFileSync(path.join(src, f), 'utf8')
            .replace(/^package com\.blaze\.player$/m, `package ${pkg}`);
          fs.writeFileSync(path.join(target, f), body, 'utf8');
        }
      }
      return cfg;
    },
  ]);
}

function withPackageRegistration(config) {
  return withMainApplication(config, cfg => {
    let c = cfg.modResults.contents;
    if (!c.includes('add(AgentPackage())')) {
      c = c.replace(
        /PackageList\(this\)\.packages\.apply \{\n/,
        m => `${m}              add(AgentPackage())\n`,
      );
    }
    cfg.modResults.contents = c;
    return cfg;
  });
}

function withActivityHook(config) {
  return withMainActivity(config, cfg => {
    let c = cfg.modResults.contents;
    if (!c.includes('AgentCommandBus.fromIntent')) {
      c = c.replace(
        /super\.onCreate\((null|savedInstanceState)\)\n/,
        m => `${m}    AgentCommandBus.fromIntent(this, intent)\n`,
      );
      if (!/override fun onNewIntent/.test(c)) {
        if (!c.includes('import android.content.Intent')) {
          c = c.replace(/^(package .+\n)/m, m => `${m}\nimport android.content.Intent\n`);
        }
        c = c.replace(
          /(class MainActivity[^{]*\{\n)/,
          m =>
            `${m}  override fun onNewIntent(intent: Intent) {\n    super.onNewIntent(intent)\n    setIntent(intent)\n    AgentCommandBus.fromIntent(this, intent)\n  }\n\n`,
        );
      }
    }
    if (!c.includes('AgentCommandBus.fromIntent(this, intent)\n    ') && !c.includes('AgentCommandBus.fromIntent')) {
      throw new Error('with-blaze-agent: could not hook MainActivity');
    }
    cfg.modResults.contents = c;
    return cfg;
  });
}

const filter = actions => ({
  action: actions.map(a => ({$: {'android:name': a}})),
  category: [{$: {'android:name': 'android.intent.category.DEFAULT'}}],
});

function withManifest(config) {
  return withAndroidManifest(config, cfg => {
    const manifest = cfg.modResults.manifest;
    manifest.permission = (manifest.permission || []).filter(
      p => p.$['android:name'] !== PERMISSION,
    );
    manifest.permission.push({
      $: {
        'android:name': PERMISSION,
        'android:protectionLevel': 'signature',
        'android:label': 'Control Blaze Player',
        'android:description': '@string/blaze_agent_permission_desc',
      },
    });
    const app = manifest.application[0];
    const drop = n => x => !(x.$ && x.$['android:name'] === n);
    app['activity-alias'] = (app['activity-alias'] || []).filter(drop('.AgentEntry'));
    app['activity-alias'].push({
      $: {
        'android:name': '.AgentEntry',
        'android:targetActivity': '.MainActivity',
        'android:exported': 'true',
        'android:permission': PERMISSION,
      },
      'intent-filter': [
        filter([
          'com.blaze.player.action.OPEN',
          'com.blaze.player.action.PLAY',
          'com.blaze.player.action.CAST',
        ]),
        {
          ...filter([
            'com.blaze.player.action.OPEN',
            'com.blaze.player.action.PLAY',
            'com.blaze.player.action.CAST',
          ]),
          data: [{$: {'android:scheme': 'content'}}, {$: {'android:scheme': 'https'}}],
        },
      ],
    });
    app.activity = (app.activity || []).filter(drop('.AgentActivity'));
    app.activity.push({
      $: {
        'android:name': '.AgentActivity',
        'android:exported': 'true',
        'android:permission': PERMISSION,
        'android:theme': '@android:style/Theme.Translucent.NoTitleBar',
        'android:excludeFromRecents': 'true',
        'android:noHistory': 'true',
      },
      'intent-filter': [
        filter([
          'com.blaze.player.action.QUEUE_ADD',
          'com.blaze.player.action.QUEUE_LIST',
          'com.blaze.player.action.QUEUE_CLEAR',
          'com.blaze.player.action.SEARCH',
        ]),
        {
          ...filter(['com.blaze.player.action.QUEUE_ADD']),
          data: [{$: {'android:scheme': 'content'}}, {$: {'android:scheme': 'https'}}],
        },
      ],
    });
    app.service = (app.service || []).filter(drop('.AgentService'));
    app.service.push({
      $: {
        'android:name': '.AgentService',
        'android:exported': 'true',
        'android:permission': PERMISSION,
      },
      'intent-filter': [{action: [{$: {'android:name': 'com.blaze.player.agent.BIND'}}]}],
    });
    return cfg;
  });
}

function withStrings(config) {
  const {withStringsXml} = require('expo/config-plugins');
  return withStringsXml(config, cfg => {
    const strings = cfg.modResults.resources.string || [];
    if (!strings.some(s => s.$.name === 'blaze_agent_permission_desc')) {
      strings.push({
        $: {name: 'blaze_agent_permission_desc'},
        _: 'Lets Blaze open, play, cast, queue and search licensed media in Blaze Player.',
      });
    }
    cfg.modResults.resources.string = strings;
    return cfg;
  });
}

module.exports = config =>
  withStrings(
    withManifest(withActivityHook(withPackageRegistration(withNativeSources(config)))),
  );
