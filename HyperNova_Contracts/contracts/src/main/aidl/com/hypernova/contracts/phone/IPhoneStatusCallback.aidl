package com.hypernova.contracts.phone;

import com.hypernova.contracts.phone.PhoneState;

oneway interface IPhoneStatusCallback {
    void onStateChanged(in PhoneState state);
}
