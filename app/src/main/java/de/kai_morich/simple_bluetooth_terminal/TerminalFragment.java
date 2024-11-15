package de.kai_morich.simple_bluetooth_terminal;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

    private Button pot1Button, pot2Button, pot3Button, pot4Button, increaseButton, decreaseButton;
    private enum Connected { False, Pending, True }

    private String deviceAddress;
    private SerialService service;

    private TextView receiveText;
    private TextView value1Label, value2Label, value3Label, value4Label;
    private TextView sendText;
    private TextUtil.HexWatcher hexWatcher;

    private Connected connected = Connected.False;
    private boolean initialStart = true;
    private boolean hexEnabled = false;
    private boolean pendingNewline = false;
    private String newline = TextUtil.newline_crlf;
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceAddress = getArguments().getString("device");
    }

    @Override
    public void onDestroy() {
        if (connected != Connected.False)
            service.disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        if(service != null)
            service.attach(this);
        else
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    @Override
    public void onStop() {
        if(service != null && !getActivity().isChangingConfigurations())
            service.detach();
        super.onStop();
    }

    @SuppressWarnings("deprecation") // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try { getActivity().unbindService(this); } catch(Exception ignored) {}
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        if(initialStart && service != null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if(initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }


    private void connect() {
        try {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            status("connecting...");
            connected = Connected.Pending;
            SerialSocket socket = new SerialSocket(getActivity().getApplicationContext(), device);
            service.connect(socket);
        } catch (Exception e) {
            onSerialConnectError(e);
        }
    }

    private void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    /*
     * starting with Android 14, notifications are not shown in notification bar by default when App is in background
     */

    private void showNotificationSettings() {
        Intent intent = new Intent();
        intent.setAction("android.settings.APP_NOTIFICATION_SETTINGS");
        intent.putExtra("android.provider.extra.APP_PACKAGE", getActivity().getPackageName());
        startActivity(intent);
    }


    /*
     * SerialListener
     */


    @Override
    public void onSerialConnectError(Exception e) {
        status("connection failed: " + e.getMessage());
        disconnect();
    }

    @Override
    public void onSerialRead(byte[] data) {
        ArrayDeque<byte[]> datas = new ArrayDeque<>();
        datas.add(data);
        receive(datas);
    }

    public void onSerialRead(ArrayDeque<byte[]> datas) {
        receive(datas);
    }
    private void disconnect() {
        connected = Connected.False;
        service.disconnect();
    }
    @Override
    public void onSerialIoError(Exception e) {
        status("connection lost: " + e.getMessage());
        disconnect();
    }
    private Button lastPressedButton;

    private void setupButtons(View view) {
        pot1Button = view.findViewById(R.id.pot1_button);
        pot2Button = view.findViewById(R.id.pot2_button);

        pot3Button = view.findViewById(R.id.pot3_button);
        pot4Button = view.findViewById(R.id.pot4_button);
        increaseButton = view.findViewById(R.id.increase_button);
        decreaseButton = view.findViewById(R.id.decrease_button);

        pot1Button.setOnClickListener(v -> send("a"));
        pot2Button.setOnClickListener(v -> send("b"));
        pot3Button.setOnClickListener(v -> send("c"));
        pot4Button.setOnClickListener(v -> send("d"));
        setupButtonClick(pot1Button, "a");
        setupButtonClick(pot2Button, "b");
        setupButtonClick(pot3Button, "c");
        setupButtonClick(pot4Button, "d");
        increaseButton.setOnClickListener(v -> send("f"));
        decreaseButton.setOnClickListener(v -> send("e"));
    }

    private void setupButtonClick(Button button, String message) {
        button.setOnClickListener(v -> {
            // إعادة لون آخر زر إلى اللون الافتراضي
            if (lastPressedButton != null) {
                lastPressedButton.setBackgroundColor(getResources().getColor(android.R.color.holo_blue_dark)); // لون افتراضي
            }

            // إرسال الرسالة المطلوبة
            send(message);

            // تحديث لون الزر الحالي إلى الأحمر وتعيينه كآخر زر مضغوط
            button.setBackgroundColor(getResources().getColor(android.R.color.holo_red_light));
            lastPressedButton = button;
        });
    }
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);

        // إعداد واجهة المستخدم وعناصر الواجهة (الأزرار والنصوص)
        receiveText = view.findViewById(R.id.receive_text);
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText));
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());
        // إعداد واجهة المستخدم وعناصر الواجهة (الأزرار والنصوص)
        value1Label = view.findViewById(R.id.value1_label);
        value2Label = view.findViewById(R.id.value2_label);
        value3Label = view.findViewById(R.id.value3_label);
        value4Label = view.findViewById(R.id.value4_label);
        setupButtons(view); // إعداد الأزرار وإضافة المستمعات للأزرار

        return view;
    }

    // تعديل دالة استقبال البيانات لتحديث قيم الأزرار بناءً على القيم المستلمة
    // إضافة المتغير هنا
    private StringBuilder incomingDataBuffer = new StringBuilder();
    private StringBuilder messageBuffer = new StringBuilder();

    // الدوال الأخرى...

    private void receive(ArrayDeque<byte[]> datas) {
        SpannableStringBuilder spn = new SpannableStringBuilder();
        SpannableStringBuilder spn2 = new SpannableStringBuilder();

        for (byte[] data : datas) {
            String msg = new String(data);

            // أضف الرسالة الجديدة إلى المتغير المؤقت
            messageBuffer.append(msg);

            // تحقق إذا كانت الرسالة تحتوي على جميع القيم المطلوبة
            String completeMessage = messageBuffer.toString();
            if (completeMessage.contains("n3.val=")) { // للتحقق من وجود آخر قيمة "n3.val="
                updatePotValues(completeMessage);

                // مسح المتغير المؤقت بعد معالجة الرسالة
                messageBuffer.setLength(0);
            }

            // معالجة الأسطر الجديدة، الخ
            if (newline.equals(TextUtil.newline_crlf) && msg.length() > 0) {
                msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf);
                if (pendingNewline && msg.charAt(0) == '\n') {
                    if (spn.length() >= 2) {
                        spn.delete(spn.length() - 2, spn.length());
                    } else {
                        Editable edt = receiveText.getEditableText();
                        if (edt != null && edt.length() >= 2)
                            edt.delete(edt.length() - 2, edt.length());
                    }
                }
                pendingNewline = msg.charAt(msg.length() - 1) == '\r';
            }
            spn.append(TextUtil.toCaretString(msg, newline.length() != 0));
        }

        receiveText.append(spn);
    }

    private void updatePotValues(String msg) {
        // تنظيف الرسالة من الأحرف غير القابلة للقراءة
        msg = msg.replaceAll("[^\\x20-\\x7E]", ""); // إزالة أي شيء خارج نطاق ASCII القابل للقراءة
        String[] values = new String[4];
        System.out.println("Cleaned msg: " + msg);

        for (int i = 0; i < 4; i++) {
            String pattern = "n" + i + "\\.val=(\\d+)";
            Pattern regex = Pattern.compile(pattern);
            Matcher matcher = regex.matcher(msg);

            if (matcher.find()) {
                values[i] = matcher.group(1);
            } else {
                values[i] = "N/A"; // للتأكد من عدم وجود قيم غير معرفة
            }
        }

        pot1Button.setText(values[0]);
        pot2Button.setText(values[1]);
        pot3Button.setText(values[2]);
        pot4Button.setText(values[3]);

        value1Label.setText("Value1=" + values[0]);
        value2Label.setText("Value2=" + values[1]);
        value3Label.setText("Value3=" + values[2]);
        value4Label.setText("Value4=" + values[3]);
    }




    @Override
    public void onServiceDisconnected(ComponentName name) {

    }

    @Override
    public void onSerialConnect() {
        status("connected");
        connected = Connected.True;
    }








    private void send(String str) {
        if(connected != Connected.True) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String msg;
            byte[] data;
            if(hexEnabled) {
                StringBuilder sb = new StringBuilder();
                TextUtil.toHexString(sb, TextUtil.fromHexString(str));
                TextUtil.toHexString(sb, newline.getBytes());
                msg = sb.toString();
                data = TextUtil.fromHexString(msg);
            } else {
                msg = str;
                data = (str + newline).getBytes();
            }
            SpannableStringBuilder spn = new SpannableStringBuilder(msg + '\n');
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            service.write(data);
        } catch (Exception e) {
            onSerialIoError(e);
        }
    }
}
