package com.clearevo.libbluetooth_gnss_service;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.ParcelUuid;
import android.os.Parcelable;


import androidx.core.app.ActivityCompat;

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.lang.reflect.Method;


public class rfcomm_conn_mgr {

    BluetoothSocket m_bluetooth_socket;
    InputStream m_sock_is;
    OutputStream m_sock_os;
    Socket m_tcp_server_sock;
    BluetoothDevice m_target_bt_server_dev;

    List<Closeable> m_cleanup_closables;
    Thread m_conn_state_watcher;

    rfcomm_conn_callbacks m_rfcomm_to_tcp_callbacks;

    ConcurrentLinkedQueue<byte[]> m_incoming_buffers;
    ConcurrentLinkedQueue<byte[]> m_outgoing_buffers;

    final int MAX_SDP_FETCH_DURATION_SECS = 15;
    final int BTINCOMING_QUEUE_MAX_LEN = 100;
    static final String TAG = "btgnss_rfcmgr";
    static final String SPP_UUID_PREFIX = "00001101";
    static final UUID SPP_WELL_KNOWN_UUNID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    String m_tcp_server_host;
    int m_tcp_server_port;
    boolean m_readline_callback_mode = false;
    boolean m_secure = true;
    volatile boolean closed = false;
    Parcelable[] m_fetched_uuids = null;
    Context m_context;
    static boolean received_disconnected = true;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (BluetoothDevice.ACTION_UUID.equals(action)) {
                // from https://stackoverflow.com/questions/14812326/android-bluetooth-get-uuids-of-discovered-devices
                // This is when we can be assured that fetchUuidsWithSdp has completed.
                // So get the uuids and call fetchUuidsWithSdp on another device in list

                Log.d(TAG, "broadcastreceiver: got BluetoothDevice.ACTION_UUID");
                BluetoothDevice deviceExtra = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                Parcelable[] uuidExtra = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID);
                Log.d(TAG, "broadcastreceiver: DeviceExtra: " + deviceExtra + " uuidExtra: " + uuidExtra);

                if (uuidExtra != null) {
                    for (Parcelable p : uuidExtra) {
                        Log.d(TAG, "in broadcastreceiver: uuidExtra parcelable part: " + p);
                    }
                    m_fetched_uuids = uuidExtra;
                } else {
                    Log.d(TAG, "broadcastreceiver: uuidExtra == null");
                }
            }
            else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                Log.d(TAG, "broadcastreceiver: got BluetoothDevice.ACTION_ACL_DISCONNECTED");
                received_disconnected=true;
            }
        }
    };


    private final BroadcastReceiver mPairReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(action)) {

                Log.d(TAG, "broadcastreceiver: got BluetoothDevice.PAIRING_REQUEST");

                BluetoothDevice mBluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                try {
                    Method m = mBluetoothDevice.getClass().getDeclaredMethod("setPin", new Class[]{byte[].class});
                    String pin_string="1234";
                    m.invoke(mBluetoothDevice, new Object[] {pin_string.getBytes()});
                                 // (Samsung) version 4.3 test phone will still pop up the user interaction page (flash), if you do not comment out the following page will not cancel but can be paired successfully. (Zhongxing, Meizu 4) (Flyme 6) version 5.1 mobile phone in both cases are normal
                    //ClsUtils.setPairingConfirmation(mBluetoothDevice.getClass(), mBluetoothDevice, true);
                    //abortBroadcast();//If the broadcast is not terminated, a matching box will appear.
                    //3. Call the setPin method to pair...
                    //Boolean ret = ClsUtils.setPin(mBluetoothDevice.getClass(), mBluetoothDevice, "The PIN you need to set");
                } catch (Exception e) {
                    Log.d(TAG, "broadcastreceiver: set pin exception "+e);
                    e.printStackTrace();
                }
                if (false) {
                    try {
                        Method m = mBluetoothDevice.getClass().getDeclaredMethod("setPairingConfirmation", boolean.class);
                        m.invoke(mBluetoothDevice, true);
                    } catch (Exception e) {
                        Log.d(TAG, "broadcastreceiver: setPairingConfirmation exception " + e);
                        e.printStackTrace();
                    }
                }
            }
        }
    };


    public static boolean is_bluetooth_on() {

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            return adapter.isEnabled();
        }

        return false;
    }


    //use this ctor for readline callback mode
    public rfcomm_conn_mgr(BluetoothDevice target_bt_server_dev, boolean secure, rfcomm_conn_callbacks cb, Context context) throws Exception {
        m_readline_callback_mode = true;
        m_secure = secure;
        init(target_bt_server_dev, secure, null, 0, cb, context);
    }

    //use this ctor and specify tcp_server_host, tcp_server_port for connect-and-stream-data-to-your-tcp-server mode
    public rfcomm_conn_mgr(BluetoothDevice target_bt_server_dev, boolean secure, final String tcp_server_host, final int tcp_server_port, rfcomm_conn_callbacks cb, Context context) throws Exception {
        init(target_bt_server_dev, secure, tcp_server_host, tcp_server_port, cb, context);
    }

    private void init(BluetoothDevice target_bt_server_dev, boolean secure, final String tcp_server_host, final int tcp_server_port, rfcomm_conn_callbacks cb, Context context) throws Exception {
        m_context = context;
        m_secure = secure;
        m_rfcomm_to_tcp_callbacks = cb;

        if (tcp_server_host == null) {
            Log.d(TAG, "tcp_server_host null so disabled conencting to tcp server mode...");
        }

        if (context == null) {
            throw new Exception("invalid context supplied is null");
        }

        if (target_bt_server_dev == null) {
            throw new Exception("invalid target_bt_server_dev supplied is null");
        }

        m_target_bt_server_dev = target_bt_server_dev;

        m_tcp_server_host = tcp_server_host;
        m_tcp_server_port = tcp_server_port;

        m_cleanup_closables = new ArrayList<Closeable>();
        m_incoming_buffers = new ConcurrentLinkedQueue<byte[]>();
        m_outgoing_buffers = new ConcurrentLinkedQueue<byte[]>();

        if (m_target_bt_server_dev == null)
            throw new Exception("m_target_bt_server_dev not specified");

        if (m_rfcomm_to_tcp_callbacks == null)
            throw new Exception("m_rfcomm_to_tcp_callbacks not specified");

        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_UUID);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        m_context.registerReceiver(mReceiver, filter);

        Log.d(TAG, "init() done m_readline_callback_mode: " + m_readline_callback_mode);
    }


    public UUID fetch_dev_uuid_with_prefix(String uuid_prefix) throws Exception {
        //BluetoothAdapter.getDefaultAdapter().cancelDiscovery();

        //always fetch fresh data from sdp - rfcomm channel numbers can change
        m_fetched_uuids = null;
        boolean fret = m_target_bt_server_dev.fetchUuidsWithSdp();
        if (!fret) {
            throw new Exception("fetchUuidsWithSdp returned false...");
        }
        Log.d(TAG, "fetch uuid started");


        final int total_wait_millis = MAX_SDP_FETCH_DURATION_SECS * 1000;
        final int fetch_recheck_steps = 30;
        final int fetch_recheck_step_duration = total_wait_millis / fetch_recheck_steps;

        for (int retry = 0; retry < fetch_recheck_steps; retry++) {

            if (m_fetched_uuids != null) {
                Log.d(TAG, "fetch uuid complete at retry: " + retry);
                break; //fetch uuid success
            }
            Thread.sleep(fetch_recheck_step_duration);
            Log.d(TAG, "fetch uuid still not complete at retry: " + retry);
        }


        if (m_fetched_uuids == null) {
            throw new Exception("failed to get uuids from target device");
        }

        UUID found_spp_uuid = null;
        for (Parcelable parcelable : m_fetched_uuids) {

            if (parcelable == null) {
                continue;
            }

            if (!(parcelable instanceof ParcelUuid))
                continue;
            ParcelUuid parcelUuid = (ParcelUuid) parcelable;

            UUID this_uuid = parcelUuid.getUuid();
            if (this_uuid == null) {
                continue;
            }

            //Log.d(TAG, "target_dev uuid: " + uuid.toString());
            //00001101-0000-1000-8000-00805f9b34fb
            if (this_uuid.toString().startsWith(uuid_prefix)) {
                found_spp_uuid = this_uuid;
            }
        }

        Log.d(TAG, "found_spp_uuid: " + found_spp_uuid);
        //BluetoothAdapter.getDefaultAdapter().cancelDiscovery();

        return found_spp_uuid;
    }

    //Reflect to call BluetoothDevice.removeBond to unpair the device
    private void unpairpairDevice(BluetoothDevice device, String pin) {
        Class<?> btClass= m_target_bt_server_dev.getClass();
        try {
            //Method m = device.getClass().getMethod("removeBond", (Class[]) null);
            Method m = btClass.getMethod("removeBond", (Class[]) null);
            m.invoke(device, (Object[]) null);
        } catch (Exception e) {
            Log.d(TAG, "removeBond exception"+e.getMessage());
        }
        if (true) {
            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
            m_context.registerReceiver(mPairReceiver, filter);

            try {
                //Method m = btClass.getMethod("createBond", (Class[]) null);
                //m.invoke(device, (Object[]) null);
                device.createBond();
            } catch (Exception e) {
                Log.d(TAG, "createBond exception" + e.getMessage());
            }
            try {
                //device.setPin(pin.getBytes());
                Method m = btClass.getDeclaredMethod("setPin", new Class[]{byte[].class});
                m.invoke(device, new Object[]
                        {pin.getBytes()});
            } catch (Exception e) {
                Log.d(TAG, "setPin exception" + e.getMessage());
            }
        }
    }

    public void connect() throws Exception {
        Log.d(TAG, "connect() start");

        try {

            try {
                if (m_bluetooth_socket != null) {
                    Log.d(TAG, "connect () m_bluetooth_socket close() requested");
                    m_bluetooth_socket.close();
                    Log.d(TAG, "connect () m_bluetooth_socket close() done");
                }
            } catch (Exception e) {
                Log.d(TAG, "connect () m_bluetooth_socket close() exception "+e);
            }
            m_bluetooth_socket = null;

            if (false) {
                try {
                    BluetoothAdapter.getDefaultAdapter().disable();
                    Thread.sleep(1000);
                    BluetoothAdapter.getDefaultAdapter().enable();
                    Thread.sleep(1000);
                } catch (Exception e) {
                    Log.d(TAG, "connect () disable/enable exception " + e);
                }
            }

            Log.d(TAG, "connect () received_disconnected " + received_disconnected);

            //if (!received_disconnected) {
            if(true){
                // unbond and bond device
                unpairpairDevice(m_target_bt_server_dev,"1234");
            }

            try {

                if (m_secure) {
                    Log.d(TAG, "createRfcommSocketToServiceRecord SPP_WELL_KNOWN_UUNID");
                    m_bluetooth_socket = m_target_bt_server_dev.createRfcommSocketToServiceRecord(SPP_WELL_KNOWN_UUNID);
                } else {
                    Log.d(TAG, "createInsecureRfcommSocketToServiceRecord SPP_WELL_KNOWN_UUNID");
                    m_bluetooth_socket = m_target_bt_server_dev.createInsecureRfcommSocketToServiceRecord(SPP_WELL_KNOWN_UUNID);
                }

                if (m_bluetooth_socket == null)
                    throw new Exception("create rfcommsocket failed - got null ret from SPP_WELL_KNOWN_UUNID sock create to dev");
            } catch (Exception e) {
                Log.d(TAG, "alternative0 - try connect using well-knwon spp uuid failed - try fetch uuids and connect with found matching spp uuid...");
                UUID found_spp_uuid = fetch_dev_uuid_with_prefix(SPP_UUID_PREFIX);
                if (found_spp_uuid == null) {
                    throw new Exception("Failed to find SPP uuid in target bluetooth device (alternative0) - ABORT");
                }
                if (m_secure) {
                    Log.d(TAG, "alt0 createRfcommSocketToServiceRecord fetcheduuid");
                    m_bluetooth_socket = m_target_bt_server_dev.createRfcommSocketToServiceRecord(found_spp_uuid);
                } else {
                    Log.d(TAG, "alt0 createInsecureRfcommSocketToServiceRecord fetcheduuid");
                    m_bluetooth_socket = m_target_bt_server_dev.createInsecureRfcommSocketToServiceRecord(found_spp_uuid);
                }

                if (m_bluetooth_socket == null)
                    throw new Exception("create rfcommsocket failed - got null ret from alternative0 sock create to dev");
            }

            BluetoothAdapter.getDefaultAdapter().cancelDiscovery();
            Log.d(TAG, "calling m_bluetooth_socket.connect() START m_target_bt_server_dev: name: "+m_target_bt_server_dev.getName() +" bdaddr: "+m_target_bt_server_dev.getAddress());
            try {
                m_bluetooth_socket.connect();
            } catch (Exception e) {
                Log.d(TAG, "calling m_bluetooth_socket.connect failed " + e);
                throw new Exception("bluetooth socket connect failed");
            }
            Log.d(TAG, "calling m_bluetooth_socket.connect() DONE m_target_bt_server_dev: name: "+m_target_bt_server_dev.getName() +" bdaddr: "+m_target_bt_server_dev.getAddress());

            OutputStream bs_os = m_bluetooth_socket.getOutputStream();
            m_cleanup_closables.add(bs_os);

            //start thread to read from m_outgoing_buffers to bluetooth socket
            queue_to_outputstream_writer_thread outgoing_thread = new queue_to_outputstream_writer_thread(m_outgoing_buffers, bs_os);
            m_cleanup_closables.add(outgoing_thread);
            outgoing_thread.start();

            if (m_rfcomm_to_tcp_callbacks != null)
                m_rfcomm_to_tcp_callbacks.on_rfcomm_connected();

            InputStream bs_is = m_bluetooth_socket.getInputStream();
            m_cleanup_closables.add(bs_is);

            //start thread to read from bluetooth socket to incoming_buffer
            inputstream_to_queue_reader_thread incoming_thread = null;
            if (m_readline_callback_mode) {
                incoming_thread = new inputstream_to_queue_reader_thread(bs_is, m_rfcomm_to_tcp_callbacks);
            } else {
                incoming_thread = new inputstream_to_queue_reader_thread(bs_is, m_incoming_buffers);
            }
            m_cleanup_closables.add(incoming_thread);
            incoming_thread.start();

            try {
                Thread.sleep(500);
            } catch (Exception e) {
                Log.d(TAG, "connect () sleep exception "+e);
            }

            if (incoming_thread.isAlive() == false)
                throw new Exception("incoming_thread died - not opening client socket...");

            if (outgoing_thread.isAlive() == false)
                throw new Exception("outgoing_thread died - not opening client socket...");

            inputstream_to_queue_reader_thread tmp_sock_is_reader_thread = null;
            queue_to_outputstream_writer_thread tmp_sock_os_writer_thread = null;

            if (m_tcp_server_host != null) {

                //open client socket to target tcp server
                Log.d(TAG, "start opening tcp socket to host: " + m_tcp_server_host + " port: " + m_tcp_server_port);
                m_tcp_server_sock = new Socket(m_tcp_server_host, m_tcp_server_port);
                m_sock_is = m_tcp_server_sock.getInputStream();
                m_sock_os = m_tcp_server_sock.getOutputStream();
                Log.d(TAG, "done opening tcp socket to host: " + m_tcp_server_host + " port: " + m_tcp_server_port);

                m_cleanup_closables.add(m_sock_is);
                m_cleanup_closables.add(m_sock_os);

                if (m_rfcomm_to_tcp_callbacks != null)
                    m_rfcomm_to_tcp_callbacks.on_target_tcp_connected();

                //start thread to read socket to outgoing_buffer
                tmp_sock_is_reader_thread = new inputstream_to_queue_reader_thread(m_sock_is, m_outgoing_buffers);
                tmp_sock_is_reader_thread.start();
                m_cleanup_closables.add(tmp_sock_is_reader_thread);

                //start thread to write from incoming buffer to socket
                tmp_sock_os_writer_thread = new queue_to_outputstream_writer_thread(m_incoming_buffers, m_sock_os);
                tmp_sock_os_writer_thread.start();
                m_cleanup_closables.add(tmp_sock_os_writer_thread);
            }

            final inputstream_to_queue_reader_thread sock_is_reader_thread = tmp_sock_is_reader_thread;
            final queue_to_outputstream_writer_thread sock_os_writer_thread = tmp_sock_os_writer_thread;


            //watch bluetooth socket state and both threads above
            m_conn_state_watcher = new Thread() {
                public void run() {
                    while (m_conn_state_watcher == this) {
                        try {

                            Thread.sleep(3000);

                            if (closed)
                                break; //if close() was called then dont notify on_bt_disconnected or on_target_tcp_disconnected

                            if (sock_is_reader_thread != null && sock_is_reader_thread.isAlive() == false) {
                                if (m_rfcomm_to_tcp_callbacks != null)
                                    m_rfcomm_to_tcp_callbacks.on_rfcomm_disconnected();
                                throw new Exception("sock_is_reader_thread died");
                            }

                            if (sock_os_writer_thread != null && sock_os_writer_thread.isAlive() == false) {
                                if (m_rfcomm_to_tcp_callbacks != null)
                                    m_rfcomm_to_tcp_callbacks.on_target_tcp_disconnected();
                                throw new Exception("sock_os_writer_thread died");
                            }

                            if (m_bluetooth_socket==null){
                                throw new Exception("bluetooth socket is null");
                            }
                            if (is_bt_connected() == false) {
                                throw new Exception("bluetooth device disconnected");
                            }

                        } catch (Exception e) {
                            if (e instanceof InterruptedException) {
                                Log.d(TAG, "rfcomm_to_tcp m_conn_state_watcher ending with signal from close()");
                            } else {
                                Log.d(TAG, "rfcomm_to_tcp m_conn_state_watcher ending with exception: " + Log.getStackTraceString(e));
                                try {
                                    if (m_rfcomm_to_tcp_callbacks != null)
                                        m_rfcomm_to_tcp_callbacks.on_rfcomm_disconnected();
                                } catch (Exception ee) {}
                            }
                            break;
                        }
                    }
                }
            };
            m_conn_state_watcher.start();
        } catch (Exception e) {
            Log.d(TAG, "connect() exception: "+Log.getStackTraceString(e));
            close();
            throw e;
        }
    }


    public boolean is_bt_connected()
    {
        try {
            return m_bluetooth_socket.isConnected();

        } catch (Exception e){
            Log.d(TAG, "is_bt_connected () exception "+e);
        }
        return false;
    }

    public void add_send_buffer(byte[] buffer)
    {
        m_outgoing_buffers.add(buffer);
    }

    public boolean isClosed()
    {
        return closed;
    }


    public synchronized void close()
    {
        if (closed)
            return;

        closed = true;
        received_disconnected=false;

        if (false) {
            try {
                if (m_context != null && mReceiver != null) {
                    m_context.unregisterReceiver(mReceiver);
                }
            } catch (Exception e) {
                Log.d(TAG, "close () unregisterReceiver exception " + e);
            }
        }

        try {
            m_conn_state_watcher.interrupt();
            m_conn_state_watcher = null;
        } catch (Exception e) {
            Log.d(TAG, "close () m_conn_state_watcher exception "+e);
        }

        try {
            Log.d(TAG,"m_bluetooth_socket close() requested");
            m_bluetooth_socket.close();
            Log.d(TAG,"m_bluetooth_socket close() done");
        } catch (Exception e) {
            Log.d(TAG, "close () m_bluetooth_socket exception "+e);
        }
        if (m_bluetooth_socket != null){
            Log.d(TAG, "close () m_bluetooth_socket isConnected: "+m_bluetooth_socket.isConnected());
        }
        m_bluetooth_socket = null;

        try {
            if (m_tcp_server_sock != null) {
                m_tcp_server_sock.close();
            }
        } catch (Exception e) {
            Log.d(TAG, "close () m_tcp_server_sock exception "+e);
        }
        m_tcp_server_sock = null;

        for (Closeable closeable : m_cleanup_closables) {
            try {
                Log.d(TAG,"m_cleanup_closables close() requested");
                closeable.close();
                Log.d(TAG,"m_cleanup_closables close() done");
            } catch (Exception e) {
                Log.d(TAG, "close () closeable exception "+e);
            }
        }
        m_cleanup_closables.clear();

        if (false) {
            try {
                int nr_sleeps = 0;
                while (!received_disconnected && (nr_sleeps < 100)) {
                    nr_sleeps++;
                    Thread.sleep(100);
                    Log.d(TAG, "close () received_disconnected: " + nr_sleeps + " " + received_disconnected);
                }
            } catch (Exception e) {
                Log.d(TAG, "close () received_disconnected exception " + e);
            }
        }
    }

}
