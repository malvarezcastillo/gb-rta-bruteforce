package stringflow.rta.libgambatte;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import stringflow.rta.Address;
import stringflow.rta.BaseGame;
import stringflow.rta.libgambatte.display.Display;
import stringflow.rta.util.ArrayUtils;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.Arrays;
import java.util.Locale;

public class Gb {
	
	private static final int BG_PALETTE = 0;
	private static final int SP1_PALETTE = 1;
	private static final int SP2_PALETTE = 2;
	private static final int LOADRES_BAD_FILE_OR_UNKNOWN_MBC = -0x7FFF;
	private static final int LOADRES_IO_ERROR = -0x1FE;
	private static final int LOADRES_UNSUPPORTED_MBC_HUC3 = -0x1FE;
	private static final int LOADRES_UNSUPPORTED_MBC_TAMA5 = -0x122;
	private static final int LOADRES_UNSUPPORTED_MBC_POCKET_CAMERA = -0x122;
	private static final int LOADRES_UNSUPPORTED_MBC_MBC7 = -0x122;
	private static final int LOADRES_UNSUPPORTED_MBC_MBC6 = -0x120;
	private static final int LOADRES_UNSUPPORTED_MBC_MBC4 = -0x117;
	private static final int LOADRES_UNSUPPORTED_MBC_MMM01 = -0x10D;
	private static final int LOADRES_OK = 0;
	
	public static final int VIDEO_BUFFER_WIDTH = 160;
	public static final int VIDEO_BUFFER_HEIGHT = 144;
	public static final int VIDEO_BUFFER_SIZE = VIDEO_BUFFER_WIDTH * VIDEO_BUFFER_HEIGHT;
	public static final int AUDIO_BUFFER_SIZE = (35112 + 2064) * 2;
	public static final int NUM_SAMPLES_PER_FRAME = 35112;
	public static final int ADDRESS_BUFFER_SIZE = 32;
	
	private Libgambatte lib;
	private Pointer gbHandle;
	
	private Display display;
	private BaseGame game;

	private Memory videoBuffer;
	private Memory audioBuffer;
	private Memory samples;
	private int frameOverflow;
	private long cycleCount;
	private int saveStateSize;
	private int currentJoypad;
	private Memory addressBuffer;
	private Memory saveStateBuffer;
	private InputCallback inputCallback;
	private boolean warnOnZero;
	
	public Gb() {
		lib = Libgambatte.INSTANCE;
		gbHandle = lib.gambatte_create();
		frameOverflow = 0;
		cycleCount = 0;
		currentJoypad = 0;
		warnOnZero = false;
		addressBuffer = new Memory(ADDRESS_BUFFER_SIZE);
		videoBuffer = new Memory(VIDEO_BUFFER_SIZE * 4);
		audioBuffer = new Memory(AUDIO_BUFFER_SIZE * 4);
		samples = new Memory(4);
		inputCallback = () -> {
			if(warnOnZero && currentJoypad == 0) {
				System.out.println("currentJoypad = 0");
			}
			return currentJoypad;
		};
		lib.gambatte_setinputgetter(gbHandle, inputCallback, null);
	}

	public void destroy() {
		if(display != null) {
			display.close();
		}
	}
	
	public void createRenderContext(int scale) {
		if(display != null) {
			return;
		}
		display = new Display(VIDEO_BUFFER_WIDTH, VIDEO_BUFFER_HEIGHT, scale, "My Display!");
	}
	
	public void loadRom(String rom, BaseGame game, int flags) {
		this.game = game;
		int result = lib.gambatte_load(gbHandle, rom, flags);
		if(result != LOADRES_OK) {
			throw new RuntimeException(String.format("Unable to load rom! Errorcode: %01X", result));
		}
	}
	
	public void loadBios(String bios) {
		int result = lib.gambatte_loadbios(gbHandle, bios, 0, 0);
		if(result < 0) {
			throw new RuntimeException(String.format("Unable to load bios! Errorcode: %02X", result));
		}
	}
	
	public int read(Object address) {
		return lib.gambatte_cpuread(gbHandle, convertAddress(address).getAddress());
	}
	
	public int read(Object offsetIn, int length) {
		int offset = convertAddress(offsetIn).getAddress();
		int result = 0;
		for(int i = 0; i < length; i++) {
			result = result << 8 ^ read(offset + i);
		}
		return result;
	}
	
	public int write(Object address, int value) {
		return lib.gambatte_cpuwrite(gbHandle, convertAddress(address).getAddress(), value);
	}
	
	public void hold(int joypad) {
		currentJoypad = joypad;
	}
	
	public void press(int joypad) {
		currentJoypad = joypad;
		frameAdvance();
		currentJoypad = 0;
	}
	
	public int frameAdvance() {
		samples.setInt(0, NUM_SAMPLES_PER_FRAME - frameOverflow);
		lib.gambatte_runfor(gbHandle, videoBuffer, VIDEO_BUFFER_WIDTH, audioBuffer, samples);
		if(display != null) {
			display.getRenderContext().drawBuffer(videoBuffer.getIntArray(0, VIDEO_BUFFER_SIZE));
			display.swapBuffers();
		}
		int hitAddress = lib.gambatte_gethitinterruptaddress(gbHandle);
		int cyclesPassed = samples.getInt(0);
		cycleCount += cyclesPassed;
		frameOverflow = (hitAddress == -1 ? 0 : frameOverflow + cyclesPassed);
//		try {
//			Thread.sleep(5);
//		} catch(InterruptedException e) {
//			e.printStackTrace();
//		}
		return hitAddress;
	}
	
	public void frameAdvance(int amount) {
		for(int i = 0; i < amount; i++) {
			frameAdvance();
		}
	}
	
	public Address advanceTo(Object... addresses) {
		int convertedAddresses[] = new int[addresses.length];
		for(int i = 0; i < addresses.length; i++) {
			convertedAddresses[i] = convertAddress(addresses[i]).getAddress();
		}
		return advanceTo(convertedAddresses);
	}
	
	private Address advanceTo(int... addresses) {
		addressBuffer.clear();
		addressBuffer.write(0, addresses, 0, addresses.length);
		int hitAddress;
		lib.gambatte_setinterruptaddresses(gbHandle, addressBuffer, addresses.length);
		do {
			hitAddress = frameAdvance();
		} while (!ArrayUtils.arrayContains(addresses, hitAddress));
		lib.gambatte_setinterruptaddresses(gbHandle, addressBuffer, 0);
		return game.getAddress(hitAddress);
	}
	
	public byte[] saveState() {
		if(saveStateSize == 0) {
			saveStateSize = lib.gambatte_savestate(gbHandle, null, VIDEO_BUFFER_WIDTH, null);
			saveStateBuffer = new Memory(saveStateSize);
		}
		lib.gambatte_savestate(gbHandle, null, VIDEO_BUFFER_WIDTH, saveStateBuffer);
		return saveStateBuffer.getByteArray(0, saveStateSize);
	}
	
	public void loadState(byte buffer[]) {
		saveStateBuffer.write(0, buffer, 0, buffer.length);
		boolean success = lib.gambatte_loadstate(gbHandle, saveStateBuffer, saveStateSize);
		if(!success) {
			throw new RuntimeException("Unable to load save state!");
		}
	}
	
	public BufferedImage saveScreenBuffer() {
		if(display == null) {
			throw new RuntimeException("You need to create a display to take a screenshot!");
		}
		BufferedImage result = new BufferedImage(display.getRenderContext().getWidth(), display.getRenderContext().getHeight(), BufferedImage.TYPE_3BYTE_BGR);
		display.getRenderContext().copyToByteArray(((DataBufferByte) result.getRaster().getDataBuffer()).getData());
		return result;
	}
	
	public long getCycleCount() {
		return cycleCount;
	}
	
	public int getRandomAdd() {
		return read(game.getRandomAdd());
	}
	
	public int getRandomSub() {
		return read(game.getRandomSub());
	}
	
	public int getRandomState() {
		return (getRandomAdd() << 8) | getRandomSub();
	}
	
	public BaseGame getGame() {
		return game;
	}
	
	public void setWarnOnZero(boolean warnOnZero) {
		this.warnOnZero = warnOnZero;
	}
	
	public double getGbpTime() {
		double cc = (double)getCycleCount();
		return ((cc / 2097152.0) + 2.135);
	}
	
	private Address convertAddress(Object address) {
		if(address instanceof String) {
			return game.getAddress((String) address);
		} else if(address instanceof Integer) {
			return game.getAddress((Integer) address);
		} else {
			throw new RuntimeException("Tried to convert invalid data-type to address.");
		}
	}
}