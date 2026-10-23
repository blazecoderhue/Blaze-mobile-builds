// Blaze Player: modified from Vega (Apache-2.0) by Blazeinchue. libtorrent4j rules removed.
const fs = require('fs');
const path = require('path');
const {withDangerousMod} = require('expo/config-plugins');

module.exports = function withProguardRules(config) {
  return withDangerousMod(config, [
    'android',
    async cfg => {
      const file = path.join(cfg.modRequest.projectRoot, 'android', 'app', 'proguard-rules.pro');
      const pkg = cfg.android?.package || 'com.blaze.player';
      if (fs.existsSync(file)) {
        let content = fs.readFileSync(file, 'utf8');
        if (!content.includes('# Blaze Player agent')) {
          content += `\n# Blaze Player agent interface and native modules\n-keep class ${pkg}.** { *; }\n`;
          fs.writeFileSync(file, content, 'utf8');
        }
      }
      return cfg;
    },
  ]);
};
