package com.clearevo.bluetooth_gnss;

import java.util.HashMap;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class GnssConnectionParams {
    public String bdaddr;
    public boolean secure;
    public boolean reconnect;
    public boolean logBtRx;
    public boolean disableNtrip;
    public boolean gapMode;
    public final Map<String, String> extraParams = new HashMap<>();
    public boolean log_location_csv;
    public boolean log_location_pos;
    public boolean log_ntrip_data;
    public boolean log_receiver_data;
    public boolean log_operations;

}
