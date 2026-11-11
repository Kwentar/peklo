package ru.ryadom.calendar;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import androidx.test.platform.app.InstrumentationRegistry;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class NowLineTest {
 @Test public void markerCoversCardsWithoutInterceptingTouches() {
  assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
  Instrumentation ins=InstrumentationRegistry.getInstrumentation();
  MainActivity a=(MainActivity)ins.startActivitySync(new Intent(ins.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
  ins.runOnMainSync(()->{
   a.handler.removeCallbacks(a.ticking);
   for(int offset:new int[]{0,1}){
    MainActivity.TimelineScale scale=new MainActivity.TimelineScale(Collections.emptyList(),0.1f);
    MainActivity.Timeline timeline=a.new Timeline(a,scale,LocalDate.now(Event.ZONE).plusDays(offset));
    View card=new View(a);card.setBackgroundColor(Color.BLUE);boolean[] clicked={false};card.setOnTouchListener((v,event)->{if(event.getAction()==MotionEvent.ACTION_UP)clicked[0]=true;return true;});
    timeline.addView(card,new FrameLayout.LayoutParams(-1,-1));
    int w=a.dp(300),h=a.dp(150);timeline.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));timeline.layout(0,0,w,h);
    Bitmap image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);timeline.draw(new Canvas(image));
    ZonedDateTime now=ZonedDateTime.now(Event.ZONE);int y=a.dp(scale.y(now.getHour()*60+now.getMinute()));int x=a.dp(150);
    boolean marker=false;for(int row=Math.max(0,y-2);row<=Math.min(h-1,y+2);row++)if(image.getPixel(x,row)==0xffc4775b)marker=true;
    assertEquals("Current-day marker must cover opaque cards only today",offset==0,marker);
    MotionEvent down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,x,y,0),up=MotionEvent.obtain(0,1,MotionEvent.ACTION_UP,x,y,0);
    timeline.dispatchTouchEvent(down);timeline.dispatchTouchEvent(up);assertTrue(clicked[0]);down.recycle();up.recycle();image.recycle();
   }
   a.finish();
  });
 }
}
