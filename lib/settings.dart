import 'package:flutter/material.dart';
import 'package:pref/pref.dart';
import 'package:flutter/services.dart';
import 'dart:math' show cos, sqrt, asin;
import 'package:modal_progress_hud_nsn/modal_progress_hud_nsn.dart';
import 'package:geolocator/geolocator.dart';

class settings_widget extends StatefulWidget {
  settings_widget(this.pref_service, Map<dynamic, dynamic> this._bd_map);
  final BasePrefService pref_service;
  Map<dynamic, dynamic> _bd_map;
  final String title = "Settings";

  @override
  settings_widget_state createState() => settings_widget_state(_bd_map);
}

class settings_widget_state extends State<settings_widget> {
  Map<dynamic, dynamic> m_bdaddr_to_name_map = new Map<dynamic, dynamic>();
  String _selected_dev = "Loading...";
  bool loading = false;

  static const method_channel =
      MethodChannel("com.clearevo.bluetooth_gnss/engine");
  static const event_channel =
      EventChannel("com.clearevo.bluetooth_gnss/settings_events");
  void toast(String msg) async {
    try {
      await method_channel.invokeMethod("toast", {"msg": msg});
    } catch (e) {
      print("WARNING: toast failed exception: $e");
    }
  }

  @override
  void initState() {
    _selected_dev = get_selected_bd_summary(widget.pref_service) ?? "";
    event_channel.receiveBroadcastStream().listen((dynamic event) async {
      Map<dynamic, dynamic> event_map = event;

      if (event_map.containsKey('callback_src')) {
        try {
          print("settings got callback event: $event_map");
        } catch (e) {
          print('parse event_map exception: $e');
        }

        if (event_map["callback_src"] == "get_mountpoint_list") {
          print("dismiss progress dialog now0");

          setState(() {
            loading = false;
          });

          //get list from native engine
          List<dynamic> ori_mpl = event_map["callback_payload"];
          print("got mpl: $ori_mpl");
          if (ori_mpl == null || ori_mpl.length == 0) {
            toast("Failed to list mount-points list from server specified...");
            return;
          }

          //conv to List<String> and sort
          List<String> mount_point_str_list =
              List<String>.generate(ori_mpl.length, (i) => "${ori_mpl[i]}");

          //filter startswith STR;
          mount_point_str_list =
              mount_point_str_list.where((s) => s.startsWith('STR;')).toList();

          //remove starting STR; to sort by mountpoint name
          mount_point_str_list = List<String>.generate(
              mount_point_str_list.length,
              (i) => "${mount_point_str_list[i].substring(4)}");

          //filter for that contains ; so wont have errors for split further below
          mount_point_str_list =
              mount_point_str_list.where((s) => s.contains(';')).toList();

          mount_point_str_list.sort();
          print("mount_point_str_list: $mount_point_str_list");

          int nmpl = mount_point_str_list.length;
          toast("Found $nmpl mountpoints...");
          bool sort_by_nearest =
              widget.pref_service.get('list_nearest_streams_first') ??
                  false;
          print('sort_by_nearest: $sort_by_nearest');

          List<Map<String, String>> mount_point_map_list =
              new List.empty(growable: true);

          for (String val in mount_point_str_list) {
            List<String> parts = val.split(";");
            //ref https://software.rtcm-ntrip.org/wiki/STR
            if (parts.length > 4) {
              mount_point_map_list.add({
                "mountpoint": parts[0],
                "identifier": parts[1],
                "lat": parts[8] ?? "0",
                "lon": parts[9] ?? "0",
                "distance_km": "0",
              });
            }
          }

          bool last_pos_valid = false;
          if (sort_by_nearest) {
            try {
              double last_lat = 0;
              double last_lon = 0;

              Position position = await Geolocator.getCurrentPosition(
                  desiredAccuracy: LocationAccuracy.high);
              last_pos_valid = position != null;
              if (position != null) {
                last_pos_valid = true;
                last_lat = position.latitude;
                last_lon = position.longitude;
              }
              print('last_pos_valid: $last_pos_valid $last_lat $last_lon');

              if (last_pos_valid) {
                //calc distance into the map in the list
                double distance_km = 999999;
                for (Map<String, String> vmap in mount_point_map_list) {
                  try {
                    double lat = double.parse(vmap["lat"].toString());
                    double lon = double.parse(vmap["lon"].toString());
                    distance_km =
                        calculateDistance(last_lat, last_lon, lat, lon);
                  } catch (e) {
                    print('parse lat/lon exception: $e');
                  }
                  vmap["distance_km"] =
                      distance_km.truncateToDouble().toString();
                }

                //sort the list according to distance: https://stackoverflow.com/questions/22177838/sort-a-list-of-maps-in-dart-second-level-sort-in-dart
                mount_point_map_list.sort((m1, m2) {
                  return double.parse(m1["distance_km"].toString())
                      .compareTo(double.parse(m2["distance_km"].toString()));
                });
              } else {
                toast("Sort by distance failed: Invalid Ref lat,lon position");
              }
            } catch (e) {
              print('sort_by_nearest exception: $e');
              toast("Sort by distance failed: $e");
            }
          }
          //make dialog to choose from mount_point_map_list

          String? chosen_mountpoint = await showDialog<String>(
              context: context,
              barrierDismissible: true,
              builder: (BuildContext context) {
                return SimpleDialog(
                  title: const Text('Select stream:'),
                  children: mount_point_map_list.map((valmap) {
                    String disp_text =
                        "${valmap["mountpoint"]}: ${valmap["identifier"]} @ ${valmap["lat"]}, ${valmap["lon"]}";
                    print(
                        "disp_text sort_by_nearest $sort_by_nearest last_pos_valid $last_pos_valid");
                    if (sort_by_nearest && last_pos_valid) {
                      disp_text += ": ${valmap["distance_km"]} km";
                    }
                    return SimpleDialogOption(
                        onPressed: () {
                          Navigator.pop(context, "${valmap["mountpoint"]}");
                        },
                        child: Text(disp_text));
                  }).toList(),
                );
              });

          print("chosen_mountpoint: $chosen_mountpoint");
          int config_nr = widget.pref_service.get('config_nr');
          String config_str = "";
          if (config_nr > 1){
            config_str = '_$config_nr';
          }
          widget.pref_service.set('ntrip_mountpoint'+config_str, chosen_mountpoint);

          //force re-load of selected ntrip_mountpoint
          Navigator.of(context).pushReplacement(
              new MaterialPageRoute(builder: (BuildContext context) {
            return new settings_widget(widget.pref_service, m_bdaddr_to_name_map);
          }));
        }
      }
    }, onError: (dynamic error) {
      print('Received error: ${error.message}');
    });
  }

  settings_widget_state(Map<dynamic, dynamic> bdaddr_to_name_map) {
    m_bdaddr_to_name_map = bdaddr_to_name_map;
  }

  String get_selected_bdaddr(BasePrefService prefService) {
    return prefService.get("target_bdaddr") ?? "";
  }

  String get_selected_bdname(BasePrefService prefService) {
    String? bdaddr = get_selected_bdaddr(prefService);
    //print("get_selected_bdname: bdaddr: $bdaddr");
    if (bdaddr == null || !(m_bdaddr_to_name_map.containsKey(bdaddr)))
      return "";
    return m_bdaddr_to_name_map[bdaddr];
  }

  String get_selected_bd_summary(BasePrefService prefService) {
    //print("get_selected_bd_summary 0");
    String ret = '';
    String bdaddr = get_selected_bdaddr(prefService);
    //print("get_selected_bd_summary selected bdaddr: $bdaddr");
    String bdname = get_selected_bdname(prefService);
    if (bdaddr.isEmpty) {
      ret += "No device selected";
    } else {
      ret += bdname;
      ret += " ($bdaddr)";
    }
    //print("get_selected_bd_summary ret $ret");
    return ret;
  }

  @override
  Widget build(BuildContext context) {
//create matching radiopreflist
    List<DropdownMenuItem> devlist = List.empty(growable: true);
    for (String bdaddr in m_bdaddr_to_name_map.keys) {
      devlist.add(DropdownMenuItem(
          value: bdaddr, child: Text(m_bdaddr_to_name_map[bdaddr].toString())));
    }

    return PrefService(
        service: widget.pref_service,
        child: MaterialApp(
      title: 'Settings',
      home: Scaffold(
          appBar: AppBar(
            title: Text('Settings'),
          ),
          body: ModalProgressHUD(
            child: PrefPage(children: [
              PrefTitle(title: Text('Target device:')),
              PrefDropdown(title: Text("Select a paired bluetooth device\n(Pair in Phone Settings > Device connection > Pair new device)"), items: devlist, pref: 'target_bdaddr'),
              PrefTitle(title: Text('Bluetooth Connection settings')),
              PrefCheckbox(
                  title: Text("Secure RFCOMM connection"), pref: 'secure'),
              PrefCheckbox(
                  title: Text("Auto-reconnect - when disconnected"),
                  pref: 'reconnect'),
              PrefCheckbox(
                  title: Text("Autostart - connect on phone boot"),
                  pref: 'autostart'),
              PrefCheckbox(
                  title: Text(
                      "Check for Settings > 'Location' ON and 'High Accuracy'"),
                  pref: 'check_settings_location'),
              PrefCheckbox(
                  title: Text("Enable Logging (location/nmea/debug-trace)"),
                  pref: 'log_bt_rx',
                  onChange: (bool? _val) async {
                    bool enable = _val!;
                    if (enable) {
                      bool write_enabled = false;
                      try {
                        write_enabled = await method_channel
                            .invokeMethod('is_write_enabled');
                      } on PlatformException catch (e) {
                        toast("WARNING: check _is_connecting failed: $e");
                      }
                      if (write_enabled == false) {
                        toast(
                            "Write external storage permission required for data loggging...");
                      }
                      try {
                        await method_channel.invokeMethod('set_log_uri');
                      } on PlatformException catch (e) {
                        toast("WARNING: set_log_uri failed: $e");
                      }
                      widget.pref_service.set('log_bt_rx',
                          false); //set by java-side mainactivity on success only
                    } else {
                      widget.pref_service.set('log_uri', "");
                    }
                  }),
              PrefTitle(title: Text('RTK/NTRIP Server settings')),
              Text(
                "Set these if your Bluetooth GNSS device supports RTK,\n(Like Ardusimple U-Blox F9, etc)",
                style: Theme.of(context).textTheme.caption,
              ),
              PrefDropdown<int>(
                title: Text('Configuration'),
                pref: 'config_nr',
                fullWidth: false,
                items: [
                  DropdownMenuItem(value: 1, child: Text('One')),
                  DropdownMenuItem(value: 2, child: Text('Two')),
                  DropdownMenuItem(value: 3, child: Text('Three')),
                  DropdownMenuItem(value: 4, child: Text('Four')),
                ]
              ),
              PrefHiderGeneric<int>(
                  pref: 'config_nr',
                  nullValue: 1,
                  reversed: true,
                  children: [
                    PrefCheckbox(title: Text("Disable NTRIP"), pref: 'disable_ntrip'),
                    PrefText(
                        label: 'Host',
                        pref: 'ntrip_host',
                        validator: (str) {
                          str = str.toString();
                          if (str == "") {
                            return "Invalid Host domain/IP";
                          }
                          return null;
                        }
                    ),
                    PrefText(
                        label: 'Port',
                        pref: 'ntrip_port',
                        validator: (str) {
                          str = str.toString();
                          int? port = intParse(str);
                          if (port == null || !(port >= 0 && port <= 65535)) {
                            return "Invalid port";
                          }
                          return null;
                        }),
                    PrefText(
                        label: "Stream (mount-point)",
                        pref: 'ntrip_mountpoint',
                        validator: (str) {
                          if (str == null) {
                            return "Invalid mount-point";
                          }
                          return null;
                        }),
                    PrefText(
                        label: 'User',
                        pref: 'ntrip_user',
                        validator: (str) {
                          return null;
                        }),
                    PrefText(
                        label: 'Password',
                        pref: 'ntrip_pass',
                        obscureText: true,
                        validator: (str) {
                          return null;
                        }),
                  ]),
              PrefHiderGeneric<int>(
              pref: 'config_nr',
              nullValue: 2,
              reversed: true,
              children: [
                PrefCheckbox(title: Text("Disable NTRIP"), pref: 'disable_ntrip_2'),
                PrefText(
                    label: 'Host',
                    pref: 'ntrip_host_2',
                    validator: (str) {
                      str = str.toString();
                      if (str == "") {
                        return "Invalid Host domain/IP";
                      }
                      return null;
                    }),
                PrefText(
                    label: 'Port',
                    pref: 'ntrip_port_2',
                    validator: (str) {
                      str = str.toString();
                      int? port = intParse(str);
                      if (port == null || !(port >= 0 && port <= 65535)) {
                        return "Invalid port";
                      }
                      return null;
                    }),
                PrefText(
                    label: "Stream (mount-point)",
                    pref: 'ntrip_mountpoint_2',
                    validator: (str) {
                      if (str == null) {
                        return "Invalid mount-point";
                      }
                      return null;
                    }),
                PrefText(
                    label: 'User',
                    pref: 'ntrip_user_2',
                    validator: (str) {
                      return null;
                    }),
                PrefText(
                    label: 'Password',
                    pref: 'ntrip_pass_2',
                    obscureText: true,
                    validator: (str) {
                      return null;
                    }),
              ]),
              PrefHiderGeneric<int>(
                  pref: 'config_nr',
                  nullValue: 3,
                  reversed: true,
                  children: [
                    PrefCheckbox(title: Text("Disable NTRIP"), pref: 'disable_ntrip_3'),
                    PrefText(
                        label: 'Host',
                        pref: 'ntrip_host_3',
                        validator: (str) {
                          str = str.toString();
                          if (str == "") {
                            return "Invalid Host domain/IP";
                          }
                          return null;
                        }),
                    PrefText(
                        label: 'Port',
                        pref: 'ntrip_port_3',
                        validator: (str) {
                          str = str.toString();
                          int? port = intParse(str);
                          if (port == null || !(port >= 0 && port <= 65535)) {
                            return "Invalid port";
                          }
                          return null;
                        }),
                    PrefText(
                        label: "Stream (mount-point)",
                        pref: 'ntrip_mountpoint_3',
                        validator: (str) {
                          if (str == null) {
                            return "Invalid mount-point";
                          }
                          return null;
                        }),
                    PrefText(
                        label: 'User',
                        pref: 'ntrip_user_3',
                        validator: (str) {
                          return null;
                        }),
                    PrefText(
                        label: 'Password',
                        pref: 'ntrip_pass_3',
                        obscureText: true,
                        validator: (str) {
                          return null;
                        }),
                  ]),
              PrefHiderGeneric<int>(
                  pref: 'config_nr',
                  nullValue: 4,
                  reversed: true,
                  children: [
                    PrefCheckbox(title: Text("Disable NTRIP"), pref: 'disable_ntrip_4'),
                    PrefText(
                        label: 'Host',
                        pref: 'ntrip_host_4',
                        validator: (str) {
                          str = str.toString();
                          if (str == "") {
                            return "Invalid Host domain/IP";
                          }
                          return null;
                        }),
                    PrefText(
                        label: 'Port',
                        pref: 'ntrip_port_4',
                        validator: (str) {
                          str = str.toString();
                          int? port = intParse(str);
                          if (port == null || !(port >= 0 && port <= 65535)) {
                            return "Invalid port";
                          }
                          return null;
                        }),
                    PrefText(
                        label: "Stream (mount-point)",
                        pref: 'ntrip_mountpoint_4',
                        validator: (str) {
                          if (str == null) {
                            return "Invalid mount-point";
                          }
                          return null;
                        }),
                    PrefText(
                        label: 'User',
                        pref: 'ntrip_user_4',
                        validator: (str) {
                          return null;
                        }),
                    PrefText(
                        label: 'Password',
                        pref: 'ntrip_pass_4',
                        obscureText: true,
                        validator: (str) {
                          return null;
                        }),
                  ]),
              Padding(
                padding: const EdgeInsets.all(5.0),
                child: ElevatedButton(
                  child: Text(
                    'List streams from above server',
                  ),
                  onPressed: () async {
                    int config_nr = widget.pref_service.get('config_nr');
                    String config_str = "";
                    if (config_nr > 1){
                      config_str = '_$config_nr';
                    }
                    String host =
                        widget.pref_service.get('ntrip_host'+config_str) ?? "";
                    String port =
                        widget.pref_service.get('ntrip_port'+config_str) ?? "";
                    String user =
                        widget.pref_service.get('ntrip_user'+config_str) ?? "";
                    String pass =
                        widget.pref_service.get('ntrip_pass'+config_str) ?? "";
                    if (host.isEmpty || port.isEmpty) {
                      toast(
                          "Please specify the ntrip_host and ntrip_port first...");
                      return;
                    }

                    int ret_code = -1;

                    try {
                      setState(() {
                        loading = true;
                      });
                      //make sure dialog shows first otherwise if no internet the .dismoiss wont work if immediate fail and progress dialog would block forever
                      new Future.delayed(const Duration(seconds: 0), () async {
                        try {
                          ret_code = await method_channel
                              .invokeMethod("get_mountpoint_list", {
                            'ntrip_host': host,
                            'ntrip_port': port,
                            'ntrip_user': user,
                            'ntrip_pass': pass,
                          });
                          print(
                              "get_mountpoint_list req waiting callback ret: $ret_code");
                        } catch (e) {
                          setState(() {
                            loading = false;
                          });
                          toast("List mount-points failed invoke: $e");
                        }
                      });
                    } catch (e) {
                      print("WARNING: Choose mount-point failed exception: $e");
                      try {
                        setState(() {
                          loading = false;
                        });
                        toast("List mount-points failed start: $e");
                      } catch (e) {}
                    }
                  },
                ),
              ),
              PrefCheckbox(
                  title: Text("Sort by nearest to to Ref lat,lon"),
                  pref: 'list_nearest_streams_first'),
              PrefTitle(title: Text('Logging settings')),
            PrefHiderGeneric<bool>(
              pref: 'log_bt_rx',
              nullValue: false,
              children: [
                PrefCheckbox(title: Text("Log location in csv format"), pref: 'log_location_csv'),
                PrefCheckbox(title: Text("Log location in pos format"), pref: 'log_location_pos'),
                PrefCheckbox(title: Text("Log NTRIP data"), pref: 'log_ntrip_data'),
                PrefCheckbox(title: Text("Log receiver data"), pref: 'log_receiver_data'),
                PrefCheckbox(title: Text("Log operations"), pref: 'log_operations'),
                ])

            ],
            ),
            inAsyncCall: loading,
          )),
    ));
  }
}

int? intParse(String input) {
  String source = input.trim();
  return int.tryParse(source);
}

//https://stackoverflow.com/questions/54138750/total-distance-calculation-from-latlng-list
double calculateDistance(lat1, lon1, lat2, lon2) {
  var p = 0.017453292519943295;
  var c = cos;
  var a = 0.5 -
      c((lat2 - lat1) * p) / 2 +
      c(lat1 * p) * c(lat2 * p) * (1 - c((lon2 - lon1) * p)) / 2;
  return 12742 * asin(sqrt(a));
}
