// Coloque este arquivo na MESMA pasta do MainActivity.java
// (android/app/src/main/java/<seu/pacote>/) e troque a linha "package" abaixo pelo pacote do seu app.
package com.alex.brasileirao;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Build;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Liga dois celulares por Bluetooth Classic (RFCOMM). Uma linha de texto = uma mensagem JSON do jogo.
 * JS -> paired() | listen() | connect({address}) | send({data}) | close()
 * Eventos: "line" {data} · "state" {state: connected|closed|error, message}
 */
@CapacitorPlugin(name = "BtLink", permissions = {
        @Permission(alias = "bt", strings = { Manifest.permission.BLUETOOTH_CONNECT })
})
public class BtLinkPlugin extends Plugin {
    private static final UUID SERVICE = UUID.fromString("8f3b2c10-6a4e-4d5a-9b1e-1c2d3e4f5a6b");

    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private volatile int gen = 0;                 // muda a cada listen/connect/close: threads antigas se ignoram
    private BluetoothServerSocket server;
    private BluetoothSocket sock;
    private volatile OutputStream out;

    private BluetoothAdapter adapter() {
        BluetoothManager m = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    // Android 12+ pede BLUETOOTH_CONNECT em tempo de execução
    private boolean ready(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 31 && getPermissionState("bt") != PermissionState.GRANTED) {
            requestPermissionForAlias("bt", call, "permCb");
            return false;
        }
        return true;
    }

    @PermissionCallback
    private void permCb(PluginCall call) {
        if (getPermissionState("bt") != PermissionState.GRANTED) { call.reject("Permissão de Bluetooth negada"); return; }
        switch (call.getMethodName()) {
            case "paired": paired(call); break;
            case "listen": listen(call); break;
            case "connect": connect(call); break;
            default: call.reject("método inválido");
        }
    }

    private BluetoothAdapter adapterOn(PluginCall call) {
        BluetoothAdapter ad = adapter();
        if (ad == null) { call.reject("Este aparelho não tem Bluetooth"); return null; }
        if (!ad.isEnabled()) { call.reject("Ligue o Bluetooth do celular"); return null; }
        return ad;
    }

    @PluginMethod
    public void paired(PluginCall call) {
        if (!ready(call)) return;
        BluetoothAdapter ad = adapterOn(call); if (ad == null) return;
        JSArray arr = new JSArray();
        for (BluetoothDevice d : ad.getBondedDevices()) {
            JSObject o = new JSObject();
            o.put("name", d.getName());
            o.put("address", d.getAddress());
            arr.put(o);
        }
        JSObject r = new JSObject();
        r.put("devices", arr);
        call.resolve(r);
    }

    // Anfitrião: abre o "servidor" e espera o amigo conectar (resolve já; a conexão chega pelo evento "state")
    @PluginMethod
    public void listen(PluginCall call) {
        if (!ready(call)) return;
        final BluetoothAdapter ad = adapterOn(call); if (ad == null) return;
        closeAll();
        final int my = ++gen;
        try {
            server = ad.listenUsingInsecureRfcommWithServiceRecord("BrasileiraoBT", SERVICE);
        } catch (IOException e) { call.reject("Não consegui abrir a sala Bluetooth"); return; }
        final BluetoothServerSocket srv = server;
        call.resolve();
        new Thread(() -> {
            try {
                BluetoothSocket s = srv.accept();           // bloqueia até alguém conectar
                try { srv.close(); } catch (IOException ignored) {}
                if (my != gen) { try { s.close(); } catch (IOException ignored) {} return; }
                attach(s, my);
            } catch (IOException e) {
                if (my == gen) emitState("error", "a sala Bluetooth foi encerrada");
            }
        }, "bt-accept").start();
    }

    // Convidado: conecta no aparelho (já pareado) do anfitrião
    @PluginMethod
    public void connect(PluginCall call) {
        if (!ready(call)) return;
        final BluetoothAdapter ad = adapterOn(call); if (ad == null) return;
        final String address = call.getString("address");
        if (address == null) { call.reject("endereço ausente"); return; }
        closeAll();
        final int my = ++gen;
        new Thread(() -> {
            BluetoothSocket s = null;
            try {
                ad.cancelDiscovery();
                s = ad.getRemoteDevice(address).createInsecureRfcommSocketToServiceRecord(SERVICE);
                s.connect();
                if (my != gen) { s.close(); call.reject("cancelado"); return; }
                attach(s, my);
                call.resolve();
            } catch (Exception e) {
                try { if (s != null) s.close(); } catch (IOException ignored) {}
                call.reject("não conectou (o anfitrião já está aguardando?)");
            }
        }, "bt-connect").start();
    }

    private void attach(final BluetoothSocket s, final int my) throws IOException {
        sock = s;
        out = s.getOutputStream();
        emitState("connected", null);
        new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    JSObject o = new JSObject();
                    o.put("data", line);
                    notifyListeners("line", o);
                }
            } catch (IOException ignored) {}
            if (my == gen) { closeAll(); emitState("closed", null); }
        }, "bt-read").start();
    }

    @PluginMethod
    public void send(PluginCall call) {
        final String data = call.getString("data");
        final OutputStream o = out;
        if (data == null || o == null) { call.reject("sem conexão"); return; }
        writer.execute(() -> {
            try {
                synchronized (o) { o.write((data + "\n").getBytes("UTF-8")); o.flush(); }
                call.resolve();
            } catch (IOException e) { call.reject("falha ao enviar"); }
        });
    }

    @PluginMethod
    public void close(PluginCall call) { closeAll(); call.resolve(); }

    private void closeAll() {
        gen++;
        out = null;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        try { if (sock != null) sock.close(); } catch (IOException ignored) {}
        server = null; sock = null;
    }

    private void emitState(String st, String msg) {
        JSObject o = new JSObject();
        o.put("state", st);
        if (msg != null) o.put("message", msg);
        notifyListeners("state", o);
    }

    @Override
    protected void handleOnDestroy() { closeAll(); }
}
