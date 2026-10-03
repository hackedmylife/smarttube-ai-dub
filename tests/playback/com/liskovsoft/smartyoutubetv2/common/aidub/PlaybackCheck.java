package com.liskovsoft.smartyoutubetv2.common.aidub;
import android.media.AudioTrack;
public class PlaybackCheck {
 public static void main(String[] args) throws Exception {
  AiDubAudioPlayer p=new AiDubAudioPlayer();p.start();p.enqueue(new byte[480000]);
  Thread.sleep(500);
  if(AudioTrack.overflow || AudioTrack.written!=480000 || AudioTrack.plays!=1) throw new AssertionError("large first packet stranded");
  p.flush(); int before=AudioTrack.plays;p.enqueue(new byte[2400]);Thread.sleep(600);
  if(AudioTrack.plays<=before) throw new AssertionError("short line stranded");p.stop();
  final float[] volume={0.7f};
  AiDubMutePolicy m=new AiDubMutePolicy(new PlayerAudioController(){public float getOriginalVolume(){return volume[0];}public void setOriginalVolume(float v){volume[0]=v;}});
  m.onEnabled();m.onStateChanged(AiDubState.DUBBING);
  if(Math.abs(volume[0]-0.056f)>0.0001)throw new AssertionError("duck");
  m.onDisabled();if(Math.abs(volume[0]-0.7f)>0.0001)throw new AssertionError("restore");
  System.out.println("PASS: large first packet, short line tail, duck and restore");
 }
}