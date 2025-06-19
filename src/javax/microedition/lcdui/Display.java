/*
	This file is part of FreeJ2ME.

	FreeJ2ME is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.

	FreeJ2ME is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
	GNU General Public License for more details.

	You should have received a copy of the GNU General Public License
	along with FreeJ2ME.  If not, see http://www.gnu.org/licenses/
*/
package javax.microedition.lcdui;

import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.Displayable;
import javax.microedition.midlet.MIDlet;

import javax.microedition.lcdui.Image;

import java.util.concurrent.atomic.AtomicReference;

import java.util.LinkedList;
import java.util.Queue;

import org.recompile.mobile.Mobile;
import org.recompile.mobile.MobilePlatform;

public class Display
{
	public static final int LIST_ELEMENT = 1;
	public static final int CHOICE_GROUP_ELEMENT = 2;
	public static final int ALERT = 3;
	public static final int COLOR_BACKGROUND = 0;
	public static final int COLOR_FOREGROUND = 1;
	public static final int COLOR_HIGHLIGHTED_BACKGROUND = 2;
	public static final int COLOR_HIGHLIGHTED_FOREGROUND = 3;
	public static final int COLOR_BORDER = 4;
	public static final int COLOR_HIGHLIGHTED_BORDER = 5;

	private Displayable current;

	private static final Queue<Runnable> serializedEvents = new LinkedList<Runnable>(), inputEvents = new LinkedList<Runnable>();

	private static final AtomicReference<Runnable> setCurrentRequest = new AtomicReference<>(), paintEvent = new AtomicReference<>();

	private Thread flashThread;

	public Display() { new Thread(this::processEvents, "EventProcessing-Thread").start(); }

	// MIDlet serial call queue methods
	public void callSerially(Runnable r) 
	{ 
		serializedEvents.add(r); 
		synchronized (serializedEvents) { serializedEvents.notify(); }
	}

	// Paint events should be serialized as well (if the event queue is empty, we need to notify the event processing thread to resume)
    public void postPaintRequest(Runnable r) 
	{ 
		paintEvent.set(r);
		synchronized (serializedEvents) 
		{
			if(serializedEvents.isEmpty()) { serializedEvents.notify(); }
		}
	}

	/* 
	 * Input events should be added in a separate thread since FreeJ2ME-Plus is the one issuing them, different from paint events which only the app will request
	 * Not doing this in a thread has the potential to freeze apps like Konami's Rush and Attack and Ratatouille.
	 * 
	 * Note that this also synchronizes on the serial queue, as input events will be processed in the same thread that serial calls and repaints are, to approximate
	 * the spec's need for them all to be serialized in respect to each other (except inputs can't be truly serialized into serializedEvents because apps like
	 * Heroes Lore: Wind of Soltia spam the queue very hard and make input processing completely unreliable)
	 */
	public void postInputEvent(Runnable r) 
	{ 
		new Thread(new Runnable() 
		{
			@Override
			public void run() 
			{
				synchronized(inputEvents) { inputEvents.add(r); }
				synchronized (serializedEvents) 
				{
					if(serializedEvents.isEmpty()) { serializedEvents.notify(); }
				}
			}
		}).start();
	}

	private void processEvents() 
	{
		Runnable call = null;
		while(true) 
		{
			/* 
			 * MIDP docs don't specify anything exact on when setCurrent should be processed, it just says it is not guaranteed to happen before the "next event delivery"
			 * so let's do it right before any events.
			 */
			call = setCurrentRequest.getAndSet(null);
			if(call != null) { call.run(); }

			synchronized (serializedEvents) 
			{
				while(serializedEvents.isEmpty() && inputEvents.isEmpty() && paintEvent.get() == null  && setCurrentRequest.get() == null) // If we have no serial events to process, and no current displayable change, wait.
				{
					try { serializedEvents.wait(); }
					catch (Exception e) { }
				}
				
				call = serializedEvents.poll(); 
				if(call != null) { call.run(); }

				// Run paint event in sync with the serial queue, always after the serial call
				call = paintEvent.getAndSet(null);
				if(call != null) { call.run(); }
			}

			// Process all pending inputs added since the previous thread loop, after any previously pending events were processed.
			synchronized(inputEvents) 
			{ 
				while(!inputEvents.isEmpty()) 
				{ 
					call = inputEvents.poll();
					if(call != null) { call.run(); }
				}
			}
		}
	}

	public void processPaintsNow() // Used by Canvas.serviceRepaints() to force repaints to be serviced
	{
		Runnable call = paintEvent.getAndSet(null);
		if(call != null) { call.run(); } // Only run Paint Events
		else { return; }
	}

	public boolean flashBacklight(int duration) 
	{
		try 
		{
			if (flashThread != null && flashThread.isAlive()) 
			{
				flashThread.interrupt();
				Mobile.renderLCDMask = false;
			}
			flashThread = new Thread(() -> 
			{
				Mobile.renderLCDMask = true;
				try { Thread.sleep((duration == Integer.MAX_VALUE) ? Long.MAX_VALUE : duration); } // If backlight is Int MAX_VALUE, that means it should stay on.
				catch(Exception e) {}
				Mobile.renderLCDMask = false;
			});
			flashThread.start();
		}
		catch(Exception e) { Mobile.log(Mobile.LOG_ERROR, Display.class.getPackage().getName() + "." + Display.class.getSimpleName() + ": " + "Failed to flash Backlight: "+ e.getMessage()); }
		return true;
	}

	public boolean vodafoneFlashBacklight(int duration, int offDuration, int reps) 
	{
		try 
		{
			if (flashThread != null && flashThread.isAlive()) 
			{
				flashThread.interrupt();
				Mobile.renderLCDMask = false;
			}
			flashThread = new Thread(() -> 
			{
				for(int i = 0; i < reps; i++) 
				{
					Mobile.renderLCDMask = true;
					try { Thread.sleep(duration);}
					catch(Exception e) {}

					Mobile.renderLCDMask = false;
					try { Thread.sleep(offDuration); }
					catch(Exception e) {}
				}
				
			});
			flashThread.start();
		}
		catch(Exception e) { Mobile.log(Mobile.LOG_ERROR, Display.class.getPackage().getName() + "." + Display.class.getSimpleName() + ": " + "Failed to flash Backlight: "+ e.getMessage()); }
		return true;
	}

	public int getBestImageHeight(int imageType)
	{
		switch(imageType)
		{
			case LIST_ELEMENT: return Mobile.getPlatform().lcdHeight / 8;
			case CHOICE_GROUP_ELEMENT: return Mobile.getPlatform().lcdHeight / 8;
			case ALERT: return Mobile.getPlatform().lcdHeight;
		}
		return Mobile.getPlatform().lcdHeight;
	}

	public int getBestImageWidth(int imageType) { return Mobile.getPlatform().lcdWidth; }

	public int getBorderStyle(boolean highlighted) 
	{ 
		if(highlighted) { return Graphics.SOLID; }
		else { return Graphics.DOTTED; }
	}

	public int getColor(int colorSpecifier)
	{
		switch(colorSpecifier)
		{
			case COLOR_BACKGROUND: return Mobile.lcduiBGColor;
			case COLOR_FOREGROUND: return Mobile.lcduiTextColor;
			case COLOR_HIGHLIGHTED_BACKGROUND: return Mobile.lcduiTextColor;
			case COLOR_HIGHLIGHTED_FOREGROUND: return Mobile.lcduiBGColor;
			case COLOR_BORDER: return Mobile.lcduiStrokeColor;
			case COLOR_HIGHLIGHTED_BORDER: return Mobile.lcduiBGColor;
		}
		return 0;
	}

	public Displayable getCurrent() { return current; }

	public static Display getDisplay(MIDlet m) 
	{
		if(m == null) { throw new NullPointerException("Cannot get a unique Display for a null MIDlet"); } 
		
		return Mobile.getDisplay(); 
	}

	public boolean isColor() { return true; }

	public int numAlphaLevels() { return 256; }

	public int numColors() { return 16777216; }

	public void setCurrent(Displayable next)
	{
		setCurrentRequest.set(new Runnable()
		{
			@Override
			public void run() 
			{
				Displayable prev;
				if (next == null || current == next) { return; }

				try 
				{
					prev = current;
					if(next instanceof Alert) { ((Alert) next).setNextScreen(current); }
					
					current = next;

					// Some versions of Harry Potter: Find Scabbers close themselves if its current displayable calls hideNotify at boot, but others do not work properly if hideNotify isn't called. 10/10 programming
					// So what we do is swap the current displayable, and then call hideNotify on the now previous displayable
					if (prev != null && prev instanceof Canvas) { prev.hideNotify(); }

					if(current instanceof Canvas) { current.showNotify(); current.notifySetCurrent(); } // Canvas always queues its rendering internally
					else { postPaintRequest(() -> { current.notifySetCurrent(); }); }

					Mobile.log(Mobile.LOG_DEBUG, Display.class.getPackage().getName() + "." + Display.class.getSimpleName() + ": " + "Set Current "+current.width+", "+current.height);
				}
				catch (Exception e)
				{
					Mobile.log(Mobile.LOG_ERROR, Display.class.getPackage().getName() + "." + Display.class.getSimpleName() + ": " + "Problem with setCurrent(next)");
					e.printStackTrace();
				}
				finally { Mobile.displayUpdated = true; }
			}
		});
		synchronized(serializedEvents) { serializedEvents.notify(); }
	}

	public void setCurrent(Alert alert, Displayable next)
	{
		setCurrentRequest.set(new Runnable()
		{
			@Override
			public void run() 
			{
				if(alert == null || next == null) { throw new NullPointerException("Cannot pass a null alert or next displayable into setCurrent(Alert, Displayable)"); }
				if(next instanceof Alert) { throw new IllegalArgumentException("Cannot pass an alert as the next screen of another alert in setCurrent(Alert, Displayable)"); }

				try
				{
					alert.setNextScreen(next);

					current = next;
					current.notifySetCurrent();

					Mobile.log(Mobile.LOG_DEBUG, Display.class.getPackage().getName() + "." + Display.class.getSimpleName() + ": " + "Set Current Alert "+current.width+", "+current.height);	
				}
				catch (Exception e)
				{
					Mobile.log(Mobile.LOG_ERROR, Display.class.getPackage().getName() + "." + Display.class.getSimpleName() + ": " + "Problem with setCurrent(alert, next)");
					e.printStackTrace();
				}
				finally { Mobile.displayUpdated = true; }
			}
		});
		synchronized(serializedEvents) { serializedEvents.notify(); }
	}

	public void setCurrentItem(Item item) 
	{
		setCurrentRequest.set(new Runnable()
		{
			@Override
			public void run() 
			{
				Form form = item.getOwner();
				if (form != null) 
				{
					if (form != current) { setCurrent(form); }
					form.focusItem(item);
				}
			}
		});
		synchronized(serializedEvents) { serializedEvents.notify(); }
	}

	public boolean vibrate(int duration)
	{
		Mobile.vibrationDuration = duration;
		Mobile.log(Mobile.LOG_DEBUG, Display.class.getPackage().getName() + "." + Display.class.getSimpleName() + ": " + "Vibrate");
		return true;
	}
}
