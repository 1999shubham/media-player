package org.rpi.main;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.Observable;
import java.util.Observer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import javax.imageio.ImageIO;

import org.apache.log4j.Logger;
import org.openhome.net.controlpoint.CpAttribute;
import org.openhome.net.controlpoint.CpDevice;
import org.openhome.net.controlpoint.CpDeviceListUpnpServiceType;
import org.openhome.net.controlpoint.ICpDeviceListListener;
import org.openhome.net.core.CombinedStack;
import org.openhome.net.core.DebugLevel;
import org.openhome.net.core.IMessageListener;
import org.openhome.net.core.InitParams;
import org.openhome.net.core.Library;
import org.openhome.net.core.NetworkAdapter;
import org.openhome.net.core.SubnetList;
import org.openhome.net.device.DvDevice;
import org.openhome.net.device.DvDeviceFactory;
import org.openhome.net.device.IDvDeviceListener;
import org.openhome.net.device.IResourceManager;
import org.openhome.net.device.IResourceWriter;
import org.rpi.airplay.AirPlayThread;
import org.rpi.alarm.Alarm;
import org.rpi.config.Config;
import org.rpi.controlpoint.DeviceInfo;
import org.rpi.controlpoint.DeviceManager;
import org.rpi.http.HttpServerGrizzly;
import org.rpi.os.OSManager;
import org.rpi.pins.PinMangerAccount;
import org.rpi.player.PlayManager;
import org.rpi.player.events.EventBase;
import org.rpi.player.events.EventSourceChanged;
import org.rpi.plugingateway.PluginGateWay;
import org.rpi.providers.IDisposableDevice;
import org.rpi.providers.PrvAVTransport;
import org.rpi.providers.PrvConnectionManager;
import org.rpi.providers.PrvCredentials;
import org.rpi.providers.PrvInfo;
import org.rpi.providers.PrvPins;
import org.rpi.providers.PrvPlayList;
import org.rpi.providers.PrvProduct;
import org.rpi.providers.PrvRadio;
import org.rpi.providers.PrvReceiver;
import org.rpi.providers.PrvRenderingControl;
import org.rpi.providers.PrvSongcast;
import org.rpi.providers.PrvTime;
import org.rpi.providers.PrvVolume;
import org.rpi.scratchpad.ws.client.EmptyClient;
import org.rpi.sources.Source;
import org.rpi.sources.SourceReader;
import org.rpi.utils.NetworkUtils;
import org.rpi.web.longpolling.PlayerStatus;

public class SimpleDevice implements IResourceManager, IDvDeviceListener, IMessageListener, Observer, ICpDeviceListListener {

	private Logger log = Logger.getLogger(SimpleDevice.class);
	private DvDevice iDevice = null;
	private Library lib = null;

	private PrvConnectionManager iConnectionManager = null;
	private PrvVolume iVolume = null;
	private PrvPlayList iPlayList = null;
	private PrvProduct iProduct = null;
	// private PrvConfig iConfig = null;
	private PrvPins iPins = null;
	private PrvInfo iInfo = null;
	private PrvTime iTime = null;
	private PrvRadio iRadio = null;
	private PrvReceiver iReceiver = null;
	private PrvAVTransport iAVTransport = null;
	private PrvRenderingControl iRenderingControl = null;
	private PrvSongcast iSongcastSender = null;
	private PrvCredentials iCredentials = null;
	private HttpServerGrizzly httpServer = null;

	private AirPlayThread airplay = null;

	private PlayManager iPlayer = PlayManager.getInstance();

	private CpDeviceListUpnpServiceType cpDeviceList = null;

	// private PinMangerAccount pinManager = new PinMangerAccount();
	private org.java_websocket.client.WebSocketClient wsc = null;


	/***
	 * Constructor for our Simple Device
	 */
	public SimpleDevice() {
		initSimpleDevice();
	}
	
	/***
	 * Constructor for our simple device.
	 * @param test
	 */
	protected SimpleDevice(boolean test) {
		// this constructor is just for test purposes...
		// do not remove it
	}

	private void startWebSocket() {
		try {
			wsc = new EmptyClient(new URI("ws://127.0.0.1:8081/PinService/endpoint?push=TIME"));
			wsc.connect();
		} catch (URISyntaxException e) {
			log.error(e);
		}

	}

	private void initSimpleDevice() {
		try {
			// startWebSocket();
			// pinManager.registerForEvent();
			PluginGateWay.getInstance().setSimpleDevice(this);
			log.debug("Creating Simple Device version: " + Config.getInstance().getVersion());
			// System.loadLibrary("ohNetJni");
			// Call the OSManager to set our path to the libohNet libraries
			OSManager.getInstance();

			InitParams initParams = new InitParams();
			initParams.setLogOutput(new OpenHomeLogger());
			if (Config.getInstance().getOpenhomePort() > 0) {
				initParams.setDvServerPort(Config.getInstance().getOpenhomePort());
			}
			// initParams.setDvEnableBonjour();
			initParams.setFatalErrorHandler(this);
			// initParams.setMsearchTimeSecs(1);

			lib = Library.create(initParams);
			lib.setDebugLevel(getDebugLevel(Config.getInstance().getOpenhomeLogLevel()));

			Inet4Address addr = NetworkUtils.getINet4Address();

			CombinedStack ds = lib.startCombined(addr);

			SubnetList subnetList = new SubnetList();
			for (int i = 0; i < subnetList.size(); i++) {
				NetworkAdapter nif = subnetList.getSubnet(i);
				if (nif.getAddress().getHostAddress().equals(addr.getHostAddress())) {
					lib.setCurrentSubnet(nif);
					break;
				}
			}
			subnetList.destroy();

			initControlPoint();

			String friendly_name = Config.getInstance().getMediaplayerFriendlyName().replace(":", " ");
			String iDeviceName = "device-" + friendly_name + "-" + NetworkUtils.getHostName() + "-MediaRenderer";
			iDevice = new DvDeviceFactory(ds.getDeviceStack()).createDeviceStandard(iDeviceName, this);
			iDevice.getAttribute("");
			log.debug("Created StandardDevice: " + iDevice.getUdn());
			iDevice.setAttribute("Upnp.IconList", this.constructIconList(iDeviceName));

			// iDevice.setAttribute("Upnp.Domain", "openhome-org");
			iDevice.setAttribute("Upnp.Domain", "upnp.org");
			iDevice.setAttribute("Upnp.Type", "MediaRenderer");
			iDevice.setAttribute("Upnp.Version", "1");
			iDevice.setAttribute("Upnp.FriendlyName", Config.getInstance().getMediaplayerFriendlyName());
			iDevice.setAttribute("Upnp.Manufacturer", "Made in Manchester");
			iDevice.setAttribute("Upnp.ModelName", "Open Home Java Renderer: v" + Config.getInstance().getVersion());
			iDevice.setAttribute("Upnp.ModelDescription", "'We Made History Not Money' - Tony Wilson..");
			iDevice.setAttribute("Upnp.PresentationUrl", "http://" + NetworkUtils.getIPAddress() + ":" + Config.getInstance().getWebServerPort() + "/MainPage.html");

			// iDevice.setAttribute("Upnp.PresentationUrl",
			// "http://192.168.1.181:8088/MainPage.html");

			// iDevice.setAttribute("Upnp.IconList" , sb.toString());
			// iDevice.setAttribute("Upnp.ModelUri", "www.google.co.uk");
			// iDevice.setAttribute("Upnp.ModelImageUri","http://upload.wikimedia.org/wikipedia/en/thumb/0/04/Joy_Division.JPG/220px-Joy_Division.JPG");
			iConnectionManager = new PrvConnectionManager(iDevice);
			iProduct = new PrvProduct(iDevice);
			iPins = new PrvPins(iDevice);
			iVolume = new PrvVolume(iDevice);
			iPlayList = new PrvPlayList(iDevice);
			iInfo = new PrvInfo(iDevice);
			iTime = new PrvTime(iDevice);
			iRadio = new PrvRadio(iDevice);
			// iInput = new PrvRadio(iDevice);
			if (Config.getInstance().isMediaplayerEnableReceiver()) {
				iReceiver = new PrvReceiver(iDevice);
			}
			if (Config.getInstance().isMediaplayerEnableAVTransport()) {
				iAVTransport = new PrvAVTransport(iDevice);
				iRenderingControl = new PrvRenderingControl(iDevice);
			}
			// iConfig = new PrvConfig(iDevice);
			iSongcastSender = new PrvSongcast(iDevice);
			iCredentials = new PrvCredentials(iDevice);
			// updateRadioList();

			try {
				SourceReader sr = new SourceReader();
				ConcurrentHashMap<String, Source> sources = sr.getSources();
				if (sources.size() == 0) {
					Source playlist = new Source("PlayList", "Playlist", "-99", true);
					sources.put(playlist.getName(), playlist);
					Source radio = new Source("Radio", "Radio", "-99", true);
					sources.put(radio.getName(), radio);
					if (Config.getInstance().isMediaplayerEnableReceiver()) {
						Source reciever = new Source("Receiver", "Receiver", "-99", true);
						sources.put(reciever.getName(), reciever);
					}
					Source airplay = new Source("AirPlay", "NetAux", "-99", false);
					sources.put(airplay.getName(), airplay);
					Source upnp = new Source(friendly_name, "UpnpAv", "-99", false);
					sources.put(friendly_name, upnp);
				}
				PluginGateWay.getInstance().setSources(sources);
				PluginGateWay.getInstance().setDefaultSourcePin(sr.getDefaultPin());
				PluginGateWay.getInstance().setStandbyPin(sr.getStandbyPin());
				for (String key : sources.keySet()) {
					Source s = sources.get(key);
					log.debug("Adding Source: " + s.toString());
					iProduct.addSource(Config.getInstance().getMediaplayerFriendlyName(), s.getName(), s.getType(), s.isVisible());
				}
				iProduct.updateCurrentSource();

			} catch (Exception e) {
				log.error("Error Reading Input Sources");
			}

			iDevice.setEnabled();
			log.debug("Device Enabled UDN: " + iDevice.getUdn());
			iProduct.setSourceByname("PlayList");

			if (Config.getInstance().isWebWerverEnabled()) {
				httpServer = new HttpServerGrizzly(Config.getInstance().getWebServerPort());
			} else {
				log.fatal("HTTP Daemon is set to false, not starting");
			}

			if (Config.getInstance().getMediaplayerStartupVolume() >= 0) {
				log.debug("Setting Startup Volume: " + Config.getInstance().getMediaplayerStartupVolume());
				PlayManager.getInstance().setVolume(Config.getInstance().getMediaplayerStartupVolume());
			} else {
				PlayManager.getInstance().setVolume(100);
			}

			try {
				// Force a updateRadioList to enable Kazoo to find Radio
				// Stations..
				updateRadioList();
			} catch (Exception e) {
				log.error("Problem with getting Radio List");
			}

			if (Config.getInstance().isAirPlayEnabled()) {
				log.info("Start AirPlay Receiver");
				airplay = new AirPlayThread(Config.getInstance().getMediaplayerFriendlyName());
				airplay.start();
			}

			PlayerStatus.getInstance();
			Alarm.getInstance();
			OSManager.getInstance().loadPlugins();
		} catch (Exception e) {
			log.error("PETE!!!!!", e);
		}
	}

	/***
	 * 
	 */
	private void initControlPoint() {
		//Create a listener for device that implement a ContentDirectory service
		cpDeviceList = new CpDeviceListUpnpServiceType("upnp.org", "ContentDirectory", 1, this);
	}



	protected String constructIconList(String deviceName) {
		StringBuffer sb = new StringBuffer();
		sb.append(this.constructIconEntry(deviceName, "image/png", ".png", "240"));
		sb.append(this.constructIconEntry(deviceName, "image/jpeg", ".jpg", "240"));
		sb.append(this.constructIconEntry(deviceName, "image/png", ".png", "120"));
		sb.append(this.constructIconEntry(deviceName, "image/jpeg", ".jpg", "120"));
		sb.append(this.constructIconEntry(deviceName, "image/png", ".png", "50"));
		sb.append(this.constructIconEntry(deviceName, "image/jpeg", ".jpg", "50"));

		return sb.toString();
	}

	protected String constructIconEntry(String deviceName, String mimeType, String fileSuffix, String size) {
		StringBuffer sb = new StringBuffer();

		sb.append("<icon>");
		sb.append("<mimetype>");
		sb.append(mimeType);
		sb.append("</mimetype>");
		sb.append("<width>");
		sb.append(size);
		sb.append("</width>");
		sb.append("<height>");
		sb.append(size);
		sb.append("</height>");
		sb.append("<depth>24</depth>");
		sb.append("<url>/" + deviceName + "/Upnp/resource/org/rpi/image/mediaplayer");
		sb.append(size);
		sb.append(fileSuffix);
		sb.append("</url>");
		sb.append("</icon>");

		return sb.toString();
	}

	private long getDebugLevel(String sLevel) {

		if (sLevel.equalsIgnoreCase("NONE")) {
			return DebugLevel.None.longValue();
		} else if (sLevel.equalsIgnoreCase("TRACE")) {
			// return DebugLevel.T.ordinal();
		} else if (sLevel.equalsIgnoreCase("NETWORK")) {
			return DebugLevel.Network.longValue();
		} else if (sLevel.equalsIgnoreCase("TIMER")) {
			return DebugLevel.Timer.longValue();
		} else if (sLevel.equalsIgnoreCase("SsdpMulticast")) {
			return DebugLevel.SsdpMulticast.longValue();
		} else if (sLevel.equalsIgnoreCase("SsdpUnicast")) {
			return DebugLevel.SsdpUnicast.longValue();
		} else if (sLevel.equalsIgnoreCase("Http")) {
			return DebugLevel.Http.longValue();
		} else if (sLevel.equalsIgnoreCase("Device")) {
			return DebugLevel.Device.longValue();
		} else if (sLevel.equalsIgnoreCase("XmlFetch")) {
			return DebugLevel.XmlFetch.longValue();
		} else if (sLevel.equalsIgnoreCase("Service")) {
			return DebugLevel.Service.longValue();
		} else if (sLevel.equalsIgnoreCase("Event")) {
			return DebugLevel.Event.longValue();
		} else if (sLevel.equalsIgnoreCase("Topology")) {
			// return DebugLevel.Topology.ordinal();
		} else if (sLevel.equalsIgnoreCase("DvInvocation")) {
			return DebugLevel.DvInvocation.longValue();
		} else if (sLevel.equalsIgnoreCase("DvInvocation")) {
			return DebugLevel.DvInvocation.longValue();
		} else if (sLevel.equalsIgnoreCase("DvEvent")) {
			return DebugLevel.DvEvent.longValue();
		} else if (sLevel.equalsIgnoreCase("DvWebSocket")) {
			return DebugLevel.DvWebSocket.longValue();
		} else if (sLevel.equalsIgnoreCase("Bonjour")) {
			return DebugLevel.Bonjour.longValue();
		} else if (sLevel.equalsIgnoreCase("DvDevice")) {
			return DebugLevel.DvDevice.longValue();
		} else if (sLevel.equalsIgnoreCase("Error")) {
			// return DebugLevel.
		} else if (sLevel.equalsIgnoreCase("All")) {
			return DebugLevel.All.longValue();
		} else if (sLevel.equalsIgnoreCase("Verbose")) {
			return DebugLevel.All.longValue();
		}
		return DebugLevel.None.longValue();
	}

	public void attachShutDownHook() {
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				log.debug("Shutdown Hook, Start of Shutdown");
				dispose();
			}
		});
		log.debug("Shut Down Hook Attached.");
	}

	public void dispose() {

		try {
			if (airplay != null) {
				airplay.stopThread();
			}
		} catch (Exception e) {
			log.error("Error Shutting Down AirPlay Server", e);
		}

		try {
			if (httpServer != null) {
				httpServer.shutdown();
			}
		} catch (Exception e) {
			log.error("Error Stopping HTTP Server:", e);
		}

		try {
			OSManager.getInstance().dispose();
		} catch (Exception e) {

		}

		if (iPlayer != null) {
			log.info("Destroying IPlayer");
			try {
				iPlayer.destroy();
				log.info("Destroyed IPlayer");
			} catch (Exception e) {
				log.error("Error Destroying IPlayer", e);
			}
		}

		if (iDevice != null) {
			log.info("Destroying device");

			try {

				iDevice.destroy();
				log.info("Destroyed device");
			} catch (Exception e) {
				log.error("Error Destroying Device", e);
			}
		}

		this.disposeDevice(iConnectionManager);
		this.disposeDevice(iPlayList);
		this.disposeDevice(iVolume);
		// this.disposeDevice(iConfig);
		this.disposeDevice(iPins);
		this.disposeDevice(iCredentials);
		this.disposeDevice(iProduct);
		this.disposeDevice(iInfo);
		this.disposeDevice(iTime);
		this.disposeDevice(iRadio);
		this.disposeDevice(iReceiver);
		this.disposeDevice(iAVTransport);
		this.disposeDevice(iRenderingControl);
		this.disposeDevice(iSongcastSender);
		// this.disposeDevice(iCredentials);

		if (this.cpDeviceList != null) {
			cpDeviceList.destroy();
		}

		if (lib != null) {
			try {
				log.info("Attempting to Close DeviceStack");
				// lib.abortProcess();
				lib.close();
				log.info("Closed DeviceStack");
			} catch (Exception e) {
				log.error("Error Closing DeviceStack", e);
			}
		}

	}

	private void disposeDevice(IDisposableDevice device) {
		if (device != null) {
			String name = device.getName();
			log.info("Dispose " + name);

			try {
				device.dispose();
				log.info("Disposed " + name);
			} catch (Exception e) {
				log.error("Error Disposing " + name, e);
			}

		}
	}

	@Override
	public void writeResource(String resource_name, int arg1, List<String> arg2, IResourceWriter writer) {
		log.info("writeResource Called: " + resource_name);
		try {
			resource_name = "/" + resource_name;
			URL url = this.getClass().getResource(resource_name);
			BufferedImage image = ImageIO.read(url);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			String fileType = "image/png";
			String format = "png";
			if (resource_name.toUpperCase().endsWith("JPG")) {
				format = "jpg";
				fileType = "image/jpeg";
			}
			ImageIO.write(image, format, baos);
			int length = baos.toByteArray().length;
			writer.writeResourceBegin(length, fileType);
			writer.writeResource(baos.toByteArray(), length);
			writer.writeResourceEnd();
		} catch (IOException e) {
			log.error("Error Writing Resource: " + resource_name, e);
		}

	}

	private void writeFile(String s) {
		try {
			File newTextFile = new File("C:/temp/thetextfile.txt");

			FileWriter fw = new FileWriter(newTextFile);
			fw.write(s);
			fw.close();

		} catch (IOException iox) {
			// do stuff with exception
			iox.printStackTrace();
		}
	}

	public PrvVolume getCustomVolume() {
		return iVolume;
	}

	@Override
	public void deviceDisabled() {
		log.info("Device has been Disabled");
		dispose();
	}

	@Override
	public void message(String paramString) {
		log.fatal("Fatal Error: " + paramString);
	}

	/**
	 * Get the Product Provider
	 * 
	 * @return
	 */
	public PrvProduct getProduct() {
		return iProduct;
	}

	private void updateRadioList() {
		try {
			// ChannelReaderJSON cr = new ChannelReaderJSON();
			// iRadio.addChannels(cr.getChannels());
			iRadio.getChannels();
		} catch (Exception e) {
			log.error("Error Reading Radio Channels");
		}
	}

	@Override
	public void update(Observable arg0, Object event) {
		EventBase base = (EventBase) event;
		switch (base.getType()) {
		case EVENTSOURCECHANGED:
			EventSourceChanged ev = (EventSourceChanged) event;
			if (ev.getSourceType().equalsIgnoreCase("RADIO")) {
				updateRadioList();
			} else {

			}
			break;
		}
	}

	@Override
	public void deviceAdded(CpDevice var1) {
		//synchronized (this) {
			try {
				CpAttribute l = var1.getAttribute("Upnp.DeviceXml");
				if (l.isAvailable()) {
					String xml = l.getValue();
					String udn = var1.getUdn();
					DeviceInfo di = new DeviceInfo(var1.getUdn(), l.getValue());
					if (di.isValid()) {
						DeviceManager.getInstance().addDevice(udn, di);
					}
					log.debug("Added: " + udn + " XML: " + xml);
				}
			} catch (Exception e) {
				log.error("Error DeviceAdded", e);
			}
		//}
	}

	@Override
	public void deviceRemoved(CpDevice var1) {
		//synchronized (this) {
			try {
				log.debug("Removed: " + var1.getUdn());
				String udn = var1.getUdn();
				DeviceManager.getInstance().deleteDevice(udn);
			} catch (Exception e) {
				log.error("Error DeviceAdded", e);
			}
		//}
	}

}
