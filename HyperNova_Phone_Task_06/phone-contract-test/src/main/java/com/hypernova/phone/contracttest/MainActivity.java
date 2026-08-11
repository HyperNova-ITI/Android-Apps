package com.hypernova.phone.contracttest;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.hypernova.contracts.phone.IPhoneCommandCallback;
import com.hypernova.contracts.phone.IPhoneCommandService;
import com.hypernova.contracts.phone.IPhoneStatusCallback;
import com.hypernova.contracts.phone.PhoneContract;
import com.hypernova.contracts.phone.PhoneResult;
import com.hypernova.contracts.phone.PhoneState;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.UUID;

public final class MainActivity extends Activity {

    private IPhoneCommandService service;
    private boolean bound;

    private TextView status;
    private TextView log;

    private EditText query;
    private EditText contactId;
    private EditText numberId;
    private EditText phoneNumber;
    private EditText callId;
    private EditText dtmf;

    private final IPhoneCommandCallback resultCallback =
            new IPhoneCommandCallback.Stub() {
                @Override
                public void onResult(PhoneResult result) {
                    runOnUiThread(
                            () -> append(
                                    "RESULT\n" +
                                    MainActivity.this.dump(result)
                            )
                    );
                }
            };

    private final IPhoneStatusCallback stateCallback =
            new IPhoneStatusCallback.Stub() {
                @Override
                public void onStateChanged(PhoneState state) {
                    runOnUiThread(
                            () -> append(
                                    "STATUS CALLBACK\n" +
                                    MainActivity.this.dump(state)
                            )
                    );
                }
            };

    private final ServiceConnection connection =
            new ServiceConnection() {
                @Override
                public void onServiceConnected(
                        ComponentName name,
                        IBinder binder
                ) {
                    service =
                            IPhoneCommandService.Stub
                                    .asInterface(binder);

                    bound = true;

                    status.setText(
                            "BOUND: " +
                            name.flattenToShortString()
                    );

                    append(
                            "Binder connected"
                    );

                    registerStateCallback();
                }

                @Override
                public void onServiceDisconnected(
                        ComponentName name
                ) {
                    service = null;
                    bound = false;
                    status.setText("DISCONNECTED");
                }

                @Override
                public void onBindingDied(
                        ComponentName name
                ) {
                    service = null;
                    bound = false;
                    status.setText("BINDING DIED");
                }

                @Override
                public void onNullBinding(
                        ComponentName name
                ) {
                    service = null;
                    bound = false;
                    status.setText("NULL BINDING");
                }
            };

    @Override
    protected void onCreate(
            Bundle savedInstanceState
    ) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        bindPhone();
    }

    @Override
    protected void onDestroy() {
        unregisterStateCallback();

        if (bound) {
            try {
                unbindService(connection);
            } catch (RuntimeException ignored) {
            }
        }

        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll =
                new ScrollView(this);

        LinearLayout root =
                new LinearLayout(this);

        root.setOrientation(
                LinearLayout.VERTICAL
        );

        int padding = dp(16);

        root.setPadding(
                padding,
                padding,
                padding,
                padding
        );

        scroll.addView(root);

        TextView title =
                new TextView(this);

        title.setText(
                "HyperNova Phone Contract Test"
        );

        title.setTextSize(24f);

        root.addView(title);

        status =
                new TextView(this);

        status.setText("NOT BOUND");
        status.setTextSize(18f);
        status.setPadding(
                0,
                dp(10),
                0,
                dp(10)
        );

        root.addView(status);

        button(root, "BIND", v -> bindPhone());

        button(
                root,
                "API VERSION",
                v -> remote(
                        "getApiVersion",
                        (s, id) ->
                                append(
                                        "API VERSION = " +
                                        s.getApiVersion()
                                )
                )
        );

        button(
                root,
                "GET CURRENT STATE",
                v -> remote(
                        "getCurrentState",
                        (s, id) ->
                                s.getCurrentState(
                                        id,
                                        resultCallback
                                )
                )
        );

        query =
                input(
                        root,
                        "Contact search query",
                        "Youssef",
                        InputType.TYPE_CLASS_TEXT
                );

        button(
                root,
                "SEARCH CONTACTS",
                v -> remote(
                        "searchContacts",
                        (s, id) ->
                                s.searchContacts(
                                        id,
                                        query.getText()
                                                .toString(),
                                        PhoneContract
                                                .DEFAULT_CONTACT_RESULT_LIMIT,
                                        resultCallback
                                )
                )
        );

        contactId =
                input(
                        root,
                        "contactId",
                        "",
                        InputType.TYPE_CLASS_NUMBER
                );

        numberId =
                input(
                        root,
                        "numberId",
                        "",
                        InputType.TYPE_CLASS_NUMBER
                );

        button(
                root,
                "GET CONTACT",
                v -> remote(
                        "getContact",
                        (s, id) ->
                                s.getContact(
                                        id,
                                        contactId.getText()
                                                .toString(),
                                        resultCallback
                                )
                )
        );

        heading(root, "Call History");

        button(
                root,
                "HISTORY: ALL",
                v -> history(
                        PhoneContract.HISTORY_FILTER_ALL
                )
        );

        button(
                root,
                "HISTORY: MISSED",
                v -> history(
                        PhoneContract.HISTORY_FILTER_MISSED
                )
        );

        button(
                root,
                "HISTORY: INCOMING",
                v -> history(
                        PhoneContract.HISTORY_FILTER_INCOMING
                )
        );

        button(
                root,
                "HISTORY: OUTGOING",
                v -> history(
                        PhoneContract.HISTORY_FILTER_OUTGOING
                )
        );

        button(
                root,
                "HISTORY: REJECTED",
                v -> history(
                        PhoneContract.HISTORY_FILTER_REJECTED
                )
        );

        button(
                root,
                "CONTACT HISTORY: ALL",
                v -> remote(
                        "getCallHistoryForContact",
                        (s, id) ->
                                s.getCallHistoryForContact(
                                        id,
                                        contactId.getText()
                                                .toString(),
                                        PhoneContract
                                                .HISTORY_FILTER_ALL,
                                        PhoneContract
                                                .DEFAULT_CALL_HISTORY_LIMIT,
                                        resultCallback
                                )
                )
        );

        heading(root, "Call Placement");

        button(
                root,
                "CALL CONTACT",
                v -> remote(
                        "callContact",
                        (s, id) ->
                                s.callContact(
                                        id,
                                        contactId.getText()
                                                .toString(),
                                        numberId.getText()
                                                .toString(),
                                        resultCallback
                                )
                )
        );

        phoneNumber =
                input(
                        root,
                        "Phone number",
                        "",
                        InputType.TYPE_CLASS_PHONE
                );

        button(
                root,
                "CALL NUMBER",
                v -> remote(
                        "callNumber",
                        (s, id) ->
                                s.callNumber(
                                        id,
                                        phoneNumber.getText()
                                                .toString(),
                                        resultCallback
                                )
                )
        );

        callId =
                input(
                        root,
                        "callId",
                        "",
                        InputType.TYPE_CLASS_NUMBER
                );

        button(
                root,
                "CALL HISTORY ENTRY",
                v -> remote(
                        "callHistoryEntry",
                        (s, id) ->
                                s.callHistoryEntry(
                                        id,
                                        callId.getText()
                                                .toString(),
                                        resultCallback
                                )
                )
        );

        heading(root, "Call Controls");

        button(
                root,
                "ANSWER",
                v -> remote(
                        "answerCall",
                        (s, id) ->
                                s.answerCall(
                                        id,
                                        resultCallback
                                )
                )
        );

        button(
                root,
                "DECLINE",
                v -> remote(
                        "declineCall",
                        (s, id) ->
                                s.declineCall(
                                        id,
                                        resultCallback
                                )
                )
        );

        button(
                root,
                "END CALL",
                v -> remote(
                        "endCall",
                        (s, id) ->
                                s.endCall(
                                        id,
                                        resultCallback
                                )
                )
        );

        button(
                root,
                "MUTE = TRUE",
                v -> muted(true)
        );

        button(
                root,
                "MUTE = FALSE",
                v -> muted(false)
        );

        button(
                root,
                "HELD = TRUE",
                v -> held(true)
        );

        button(
                root,
                "HELD = FALSE",
                v -> held(false)
        );

        heading(root, "Audio Route");

        button(
                root,
                "ROUTE: VEHICLE",
                v -> audio(
                        PhoneContract.AUDIO_ROUTE_VEHICLE
                )
        );

        button(
                root,
                "ROUTE: PHONE",
                v -> audio(
                        PhoneContract.AUDIO_ROUTE_PHONE
                )
        );

        button(
                root,
                "ROUTE: BLUETOOTH",
                v -> audio(
                        PhoneContract.AUDIO_ROUTE_BLUETOOTH
                )
        );

        dtmf =
                input(
                        root,
                        "DTMF [0-9*#]",
                        "1",
                        InputType.TYPE_CLASS_PHONE
                );

        button(
                root,
                "SEND DTMF",
                v -> remote(
                        "sendDtmf",
                        (s, id) ->
                                s.sendDtmf(
                                        id,
                                        dtmf.getText()
                                                .toString(),
                                        resultCallback
                                )
                )
        );

        heading(root, "Realtime Status");

        button(
                root,
                "REGISTER STATUS CALLBACK",
                v -> registerStateCallback()
        );

        button(
                root,
                "UNREGISTER STATUS CALLBACK",
                v -> unregisterStateCallback()
        );

        button(
                root,
                "CLEAR LOG",
                v -> log.setText("")
        );

        heading(root, "Results");

        log =
                new TextView(this);

        log.setTextSize(13f);
        log.setTextIsSelectable(true);
        log.setPadding(
                0,
                dp(8),
                0,
                dp(48)
        );

        root.addView(log);

        return scroll;
    }

    private void history(
            int filter
    ) {
        remote(
                "getCallHistory",
                (s, id) ->
                        s.getCallHistory(
                                id,
                                filter,
                                PhoneContract
                                        .DEFAULT_CALL_HISTORY_LIMIT,
                                resultCallback
                        )
        );
    }

    private void muted(
            boolean value
    ) {
        remote(
                "setMuted(" + value + ")",
                (s, id) ->
                        s.setMuted(
                                id,
                                value,
                                resultCallback
                        )
        );
    }

    private void held(
            boolean value
    ) {
        remote(
                "setHeld(" + value + ")",
                (s, id) ->
                        s.setHeld(
                                id,
                                value,
                                resultCallback
                        )
        );
    }

    private void audio(
            int route
    ) {
        remote(
                "setAudioRoute(" + route + ")",
                (s, id) ->
                        s.setAudioRoute(
                                id,
                                route,
                                resultCallback
                        )
        );
    }

    private void bindPhone() {
        if (
                bound &&
                service != null
        ) {
            append("Already bound");
            return;
        }

        Intent intent =
                new Intent(
                        PhoneContract
                                .BIND_COMMAND_ACTION
                );

        intent.setPackage(
                PhoneContract.PACKAGE_NAME
        );

        try {
            boolean accepted =
                    bindService(
                            intent,
                            connection,
                            Context.BIND_AUTO_CREATE
                    );

            status.setText(
                    accepted
                            ? "BIND REQUESTED"
                            : "BIND REJECTED"
            );

            append(
                    "bindService() = " +
                    accepted
            );

        } catch (
                SecurityException exception
        ) {
            status.setText(
                    "PERMISSION DENIED"
            );

            append(
                    "BIND SECURITY EXCEPTION\n" +
                    exception
            );
        }
    }

    private void registerStateCallback() {
        if (
                service == null
        ) {
            append(
                    "Cannot register: NOT BOUND"
            );
            return;
        }

        try {
            service.registerPhoneStatusCallback(
                    stateCallback
            );

            append(
                    "Status callback registered"
            );

        } catch (
                RemoteException exception
        ) {
            append(
                    "Register callback failed\n" +
                    exception
            );
        }
    }

    private void unregisterStateCallback() {
        if (
                service == null
        ) {
            return;
        }

        try {
            service.unregisterPhoneStatusCallback(
                    stateCallback
            );

            append(
                    "Status callback unregistered"
            );

        } catch (
                RemoteException exception
        ) {
            append(
                    "Unregister callback failed\n" +
                    exception
            );
        }
    }

    private void remote(
            String name,
            RemoteAction action
    ) {
        IPhoneCommandService current =
                service;

        if (
                current == null
        ) {
            append(
                    name +
                    ": NOT BOUND"
            );
            return;
        }

        String requestId =
                UUID.randomUUID()
                        .toString();

        append(
                "REQUEST " +
                name +
                "\nrequestId=" +
                requestId
        );

        try {
            action.run(
                    current,
                    requestId
            );

        } catch (
                RemoteException |
                RuntimeException exception
        ) {
            append(
                    name +
                    " failed\n" +
                    exception
            );
        }
    }

    private interface RemoteAction {
        void run(
                IPhoneCommandService service,
                String requestId
        ) throws RemoteException;
    }

    private EditText input(
            LinearLayout root,
            String hint,
            String initial,
            int type
    ) {
        EditText value =
                new EditText(this);

        value.setHint(hint);
        value.setText(initial);
        value.setInputType(type);
        value.setSingleLine(true);

        root.addView(
                value,
                params()
        );

        return value;
    }

    private void heading(
            LinearLayout root,
            String text
    ) {
        TextView value =
                new TextView(this);

        value.setText(text);
        value.setTextSize(19f);
        value.setPadding(
                0,
                dp(20),
                0,
                dp(6)
        );

        root.addView(value);
    }

    private void button(
            LinearLayout root,
            String text,
            View.OnClickListener click
    ) {
        Button value =
                new Button(this);

        value.setText(text);
        value.setAllCaps(false);
        value.setOnClickListener(click);

        root.addView(
                value,
                params()
        );
    }

    private LinearLayout.LayoutParams params() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams
                        .MATCH_PARENT,
                LinearLayout.LayoutParams
                        .WRAP_CONTENT
        );
    }

    private int dp(
            int value
    ) {
        return Math.round(
                value *
                getResources()
                        .getDisplayMetrics()
                        .density
        );
    }

    private void append(
            String value
    ) {
        if (
                log == null
        ) {
            return;
        }

        String previous =
                log.getText()
                        .toString();

        log.setText(
                previous.isEmpty()
                        ? value
                        : previous +
                            "\n\n" +
                            value
        );
    }

    private String dump(
            Object value
    ) {
        StringBuilder out =
                new StringBuilder();

        dumpValue(
                out,
                value,
                0,
                new IdentityHashMap<>()
        );

        return out.toString();
    }

    private void dumpValue(
            StringBuilder out,
            Object value,
            int depth,
            IdentityHashMap<Object, Boolean> seen
    ) {
        if (
                value == null
        ) {
            out.append("null");
            return;
        }

        if (
                depth > 5
        ) {
            out.append("<max-depth>");
            return;
        }

        if (
                value instanceof String ||
                value instanceof Number ||
                value instanceof Boolean ||
                value instanceof Character
        ) {
            out.append(
                    String.valueOf(value)
            );
            return;
        }

        if (
                value instanceof List<?>
        ) {
            List<?> list =
                    (List<?>) value;

            out.append("[\n");

            for (
                    int i = 0;
                    i < list.size();
                    i++
            ) {
                indent(out, depth + 1);
                out.append(i).append(": ");

                dumpValue(
                        out,
                        list.get(i),
                        depth + 1,
                        seen
                );

                out.append("\n");
            }

            indent(out, depth);
            out.append("]");
            return;
        }

        if (
                seen.containsKey(value)
        ) {
            out.append("<cycle>");
            return;
        }

        seen.put(value, Boolean.TRUE);

        Class<?> type =
                value.getClass();

        out.append(
                type.getSimpleName()
        ).append(" {\n");

        boolean fieldsWritten =
                false;

        for (
                Field field :
                type.getDeclaredFields()
        ) {
            if (
                    Modifier.isStatic(
                            field.getModifiers()
                    )
            ) {
                continue;
            }

            fieldsWritten = true;

            indent(out, depth + 1);
            out.append(
                    field.getName()
            ).append(" = ");

            try {
                field.setAccessible(true);

                dumpValue(
                        out,
                        field.get(value),
                        depth + 1,
                        seen
                );

            } catch (
                    ReflectiveOperationException |
                    RuntimeException exception
            ) {
                out.append("<unavailable>");
            }

            out.append("\n");
        }

        if (
                !fieldsWritten
        ) {
            indent(out, depth + 1);
            out.append(
                    String.valueOf(value)
            ).append("\n");
        }

        indent(out, depth);
        out.append("}");

        seen.remove(value);
    }

    private void indent(
            StringBuilder out,
            int depth
    ) {
        for (
                int i = 0;
                i < depth;
                i++
        ) {
            out.append("  ");
        }
    }
}
