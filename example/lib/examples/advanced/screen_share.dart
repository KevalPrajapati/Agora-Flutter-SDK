import 'dart:developer';

import 'package:agora_rtc_engine/rtc_engine.dart';
import 'package:agora_rtc_engine/rtc_local_view.dart' as RtcLocalView;
import 'package:agora_rtc_engine/rtc_remote_view.dart' as RtcRemoteView;
import 'package:agora_rtc_engine_example/config/agora.config.dart' as config;
import 'package:flutter/cupertino.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';

/// ScreenShare Example
class ScreenShare extends StatefulWidget {
  @override
  State<StatefulWidget> createState() => _State();
}

class _State extends State<ScreenShare> {
  RtcEngine? engine;
  String channelId = config.channelId;
  bool startPreview = false,
      isJoined = false,
      switchCamera = true,
      shareScreen =false,
      switchRender = true;
  List<int> remoteUid = [];
  TextEditingController? _controller;

  @override
  void initState() {
    super.initState();
    _controller = TextEditingController(text: channelId);
    _initEngine();
  }

  @override
  void dispose() {
    super.dispose();
    engine?.destroy();
  }

  _initEngine() {
    RtcEngine.createWithConfig(RtcEngineConfig(config.appId)).then((value) {
      setState(() {
        engine = value;
        _addListeners();
            () async {
          await engine?.enableVideo();
          await engine?.startPreview();
          await engine?.setChannelProfile(ChannelProfile.LiveBroadcasting);
          await engine?.setClientRole(ClientRole.Broadcaster);
          setState(() {
            startPreview = true;
          });
        }();
      });
    });
  }

  _addListeners() {
    engine?.setEventHandler(RtcEngineEventHandler(
      warning: (warningCode) {
        log('warning ${warningCode}');
      },
      error: (errorCode) {
        log('error ${errorCode}');
      },
      joinChannelSuccess: (channel, uid, elapsed) {
        log('joinChannelSuccess ${channel} ${uid} ${elapsed}');
        setState(() {
          isJoined = true;
        });
      },
      userJoined: (uid, elapsed) {
        log('userJoined  ${uid} ${elapsed}');
        setState(() {
          remoteUid.add(uid);
        });
      },
      remoteVideoStateChanged: (uid, state, reason, elapsed) {
        log('remoteVideoStateChanged ${uid} ${state} ${reason} ${elapsed}');
        // if (state == VideoRemoteState.Decoding) {
        //   setState(() {
        //     remoteUid.add(uid);
        //   });
        // }
      },
      userOffline: (uid, reason) {
        log('userOffline  ${uid} ${reason}');
        setState(() {
          remoteUid.removeWhere((element) => element == uid);
        });
      },
      leaveChannel: (stats) {
        log('leaveChannel ${stats.toJson()}');
        setState(() {
          isJoined = false;
          remoteUid.clear();
        });
      },
    ));
  }

  _joinChannel() async {
    if (defaultTargetPlatform == TargetPlatform.android) {
      await [Permission.microphone, Permission.camera].request();
    }
    await engine?.joinChannel(config.token, channelId, null, config.uid);
  }

  _leaveChannel() async {
    await engine?.leaveChannel();
  }

  _switchCamera() {
    engine?.switchCamera().then((value) {
      setState(() {
        switchCamera = !switchCamera;
      });
    }).catchError((err) {
      log('switchCamera $err');
    });
  }

  _shareScreen(){
    engine?.shareScreen(context).then((value) {
      setState(() {
        shareScreen = !shareScreen;
      });
    }).catchError((err) {
      log('shareScreen $err');
    });
  }

  _switchRender() {
    setState(() {
      switchRender = !switchRender;
      remoteUid = List.of(remoteUid.reversed);
    });
  }

  @override
  Widget build(BuildContext context) {
    return Stack(
      children: [
        Column(
          children: [
            TextField(
              controller: _controller,
              decoration: InputDecoration(hintText: 'Channel ID'),
              onChanged: (text) {
                setState(() {
                  channelId = text;
                });
              },
            ),
            Row(
              children: [
                Expanded(
                  flex: 1,
                  child: ElevatedButton(
                    onPressed: isJoined ? _leaveChannel : _joinChannel,
                    child: Text('${isJoined ? 'Leave' : 'Join'} channel'),
                  ),
                )
              ],
            ),
            _renderVideo(),
          ],
        ),
        if (defaultTargetPlatform == TargetPlatform.android ||
            defaultTargetPlatform == TargetPlatform.iOS)
          Align(
            alignment: Alignment.bottomRight,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                ElevatedButton(
                  onPressed: _shareScreen,
                  child: Text('${shareScreen ? 'Stop Sharing' : 'Share Screen'}'),
                ),
              ],
            ),
          )
      ],
    );
  }

  _renderVideo() {
    return Expanded(
      child: Stack(
        children: [
          if (startPreview)
            kIsWeb ? RtcLocalView.SurfaceView() : RtcLocalView.TextureView(),
          Align(
            alignment: Alignment.topLeft,
            child: SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(
                children: List.of(remoteUid.map(
                      (e) => GestureDetector(
                    onTap: _switchRender,
                    child: Container(
                      width: 120,
                      height: 120,
                      child: kIsWeb
                          ? RtcRemoteView.SurfaceView(
                        uid: e,
                        channelId: channelId,
                      )
                          : RtcRemoteView.TextureView(
                        uid: e,
                        channelId: channelId,
                      ),
                    ),
                  ),
                )),
              ),
            ),
          )
        ],
      ),
    );
  }
}
