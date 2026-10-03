package android.media;
public class AudioTrack {
 public static final int MODE_STREAM=1, STATE_INITIALIZED=1;
 public static volatile int written, plays; public static volatile boolean overflow;
 private int seeded; private boolean playing; private final int capacity;
 public AudioTrack(int a,int b,int c,int d,int size,int f){capacity=size;}
 public static int getMinBufferSize(int a,int b,int c){return 1024;}
 public int getState(){return 1;} public void release(){} public void stop(){}
 public void pause(){playing=false;} public void flush(){seeded=0;}
 public void play(){playing=true;plays++;}
 public int write(byte[] data,int off,int len){
  if(!playing && seeded+len>capacity){overflow=true;return -1;}
  seeded+=len;written+=len;return len;
 }
}