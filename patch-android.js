// Roda no GitHub depois de "npx cap add android": instala o plugin Bluetooth e ajusta o app.
const fs = require('fs'), path = require('path');
const pkg = 'com.alex.brasileirao';
const dir = path.join('android', 'app', 'src', 'main', 'java', ...pkg.split('.'));
fs.mkdirSync(dir, { recursive: true });
fs.copyFileSync('BtLinkPlugin.java', path.join(dir, 'BtLinkPlugin.java'));
fs.writeFileSync(path.join(dir, 'MainActivity.java'),
`package ${pkg};

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
  @Override
  public void onCreate(Bundle savedInstanceState) {
    registerPlugin(BtLinkPlugin.class);
    super.onCreate(savedInstanceState);
  }
}
`);
const mf = path.join('android', 'app', 'src', 'main', 'AndroidManifest.xml');
let x = fs.readFileSync(mf, 'utf8');
if (!x.includes('BLUETOOTH_CONNECT')) {
  x = x.replace('<application',
`<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <application`);
}
if (!x.includes('screenOrientation')) x = x.replace('<activity', '<activity android:screenOrientation="sensorLandscape"');
fs.writeFileSync(mf, x);
console.log('Plugin Bluetooth instalado.');
