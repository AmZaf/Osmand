package net.osmand.plus;

import java.io.File;
import java.util.List;
import java.util.Stack;

import org.apache.commons.logging.Log;

import android.os.Handler;
import android.os.Looper;

import net.osmand.Algoritms;
import net.osmand.LogUtil;
import net.osmand.ResultMatcher;
import net.osmand.data.Amenity;
import net.osmand.data.MapTileDownloader;
import net.osmand.data.TransportStop;
import net.osmand.data.MapTileDownloader.DownloadRequest;
import net.osmand.data.MapTileDownloader.IMapDownloaderCallback;
import net.osmand.map.ITileSource;

/**
 * Thread to load map objects (POI, transport stops )async
 */
public class AsyncLoadingThread extends Thread {
	
	public static final int LIMIT_TRANSPORT = 200;
	
	private static final Log log = LogUtil.getLog(AsyncLoadingThread.class); 
	
	private Handler asyncLoadingPoi; 
	private Handler asyncLoadingTransport;
	
	Stack<Object> requests = new Stack<Object>();
	AmenityLoadRequest poiLoadRequest = null;
	TransportLoadRequest transportLoadRequest = null;
	
	private static final MapTileDownloader downloader = MapTileDownloader.getInstance();
	
	private final ResourceManager resourceManger;

	public AsyncLoadingThread(ResourceManager resourceManger) {
		super("Loader map objects (synchronizer)"); //$NON-NLS-1$
		this.resourceManger = resourceManger;
	}
	
	private void startPoiLoadingThread() {
		if (asyncLoadingPoi == null) {
			Thread th = new Thread(new Runnable() {
				@Override
				public void run() {
					Looper.prepare();
					asyncLoadingPoi = new Handler();
					Looper.loop();
				}
			}, "Loading poi");
			th.start();
		}
		while(asyncLoadingPoi != null){
			// wait
		}
	}
	
	private void startTransportLoadingThread() {
		new Thread(new Runnable() {
			@Override
			public void run() {
				Looper.prepare();
				asyncLoadingTransport = new Handler();
				Looper.loop();
			}
		}, "Loading transport").start();
		while(asyncLoadingTransport != null){
			// wait
		}
	}

	private int calculateProgressStatus(){
		int progress = 0;
		if (downloader.isSomethingBeingDownloaded()) {
			progress = BusyIndicator.STATUS_GREEN;
		} else if (resourceManger.getContext().getRoutingHelper().isRouteBeingCalculated()) {
			progress = BusyIndicator.STATUS_BLUE;
		} else if (!requests.isEmpty()) {
			progress = BusyIndicator.STATUS_BLACK;
		} else if(poiLoadRequest != null && !poiLoadRequest.isFinished()) {
			progress = BusyIndicator.STATUS_BLACK;
		} else if(transportLoadRequest != null && !transportLoadRequest.isFinished()) {
			progress = BusyIndicator.STATUS_BLACK;
		}
		return progress;
	}

	@Override
	public void run() {
		while (true) {
			try {
				boolean tileLoaded = false;
				boolean amenityLoaded = false;
				boolean transportLoaded = false;
				boolean mapLoaded = false;
				
				int progress = calculateProgressStatus();
				synchronized (resourceManger) {
					if (resourceManger.getBusyIndicator() != null) {
						resourceManger.getBusyIndicator().updateStatus(progress);
					}
				}
				while (!requests.isEmpty()) {
					Object req = requests.pop();
					if (req instanceof TileLoadDownloadRequest) {
						TileLoadDownloadRequest r = (TileLoadDownloadRequest) req;
						tileLoaded |= resourceManger.getRequestedImageTile(r) != null;
					} else if (req instanceof AmenityLoadRequest) {
						if (!amenityLoaded) {
							if (poiLoadRequest == null || asyncLoadingPoi == null) {
								startPoiLoadingThread();
								poiLoadRequest = (AmenityLoadRequest) req;
								asyncLoadingPoi.post(poiLoadRequest.prepareToRun());
							} else if (poiLoadRequest.recalculateRequest((AmenityLoadRequest) req)) {
								poiLoadRequest = (AmenityLoadRequest) req;
								asyncLoadingPoi.post(poiLoadRequest.prepareToRun());
							}
							amenityLoaded = true;
						}
					} else if (req instanceof TransportLoadRequest) {
						if (!transportLoaded) {
							if (transportLoadRequest == null || asyncLoadingTransport == null) {
								startTransportLoadingThread();
								transportLoadRequest = (TransportLoadRequest) req;
								asyncLoadingTransport.post(transportLoadRequest.prepareToRun());
							} else if (transportLoadRequest.recalculateRequest((TransportLoadRequest) req)) {
								transportLoadRequest = (TransportLoadRequest) req;
								asyncLoadingTransport.post(transportLoadRequest.prepareToRun());
							}
							transportLoaded = true;
						}
					} else if (req instanceof MapLoadRequest) {
						if (!mapLoaded) {
							MapLoadRequest r = (MapLoadRequest) req;
							resourceManger.getRenderer().loadMap(r.tileBox, downloader.getDownloaderCallbacks());
							mapLoaded = true;
						}
					}
				}
				if (tileLoaded || amenityLoaded || transportLoaded || mapLoaded) {
					// use downloader callback
					for (IMapDownloaderCallback c : downloader.getDownloaderCallbacks()) {
						c.tileDownloaded(null);
					}
				}
				int newProgress = calculateProgressStatus();
				if (progress != newProgress) {
					synchronized (resourceManger) {
						if (resourceManger.getBusyIndicator() != null) {
							resourceManger.getBusyIndicator().updateStatus(newProgress);
						}
					}
				}
				sleep(750);
			} catch (InterruptedException e) {
				log.error(e, e);
			} catch (RuntimeException e) {
				log.error(e, e);
			}
		}
	}

	public void requestToLoadImage(TileLoadDownloadRequest req) {
		requests.push(req);
	}

	public void requestToLoadAmenities(AmenityLoadRequest req) {
		requests.push(req);
	}

	public void requestToLoadMap(MapLoadRequest req) {
		requests.push(req);
	}

	public void requestToLoadTransport(TransportLoadRequest req) {
		requests.push(req);
	}
	
	public boolean isFileCurrentlyDownloaded(File fileToSave) {
		return downloader.isFileCurrentlyDownloaded(fileToSave);
	}

	public void requestToDownload(TileLoadDownloadRequest req) {
		downloader.requestToDownload(req);
	}

	protected static class TileLoadDownloadRequest extends DownloadRequest {

		public final String tileId;
		public final File dirWithTiles;
		public final ITileSource tileSource;

		public TileLoadDownloadRequest(File dirWithTiles, String url, File fileToSave, String tileId, ITileSource source, int tileX,
				int tileY, int zoom) {
			super(url, fileToSave, tileX, tileY, zoom);
			this.dirWithTiles = dirWithTiles;
			this.tileSource = source;
			this.tileId = tileId;
		}
	}

	protected static class MapObjectLoadRequest<T> implements ResultMatcher<T> {
		protected double topLatitude;
		protected double bottomLatitude;
		protected double leftLongitude;
		protected double rightLongitude;
		protected boolean cancelled = false;
		protected volatile boolean finished = false;

		public boolean isContains(double topLatitude, double leftLongitude, double bottomLatitude, double rightLongitude) {
			boolean inside = this.topLatitude >= topLatitude && this.leftLongitude <= leftLongitude
					&& this.rightLongitude >= rightLongitude && this.bottomLatitude <= bottomLatitude;
			return inside;
		}

		public void setBoundaries(double topLatitude, double leftLongitude, double bottomLatitude, double rightLongitude) {
			this.topLatitude = topLatitude;
			this.bottomLatitude = bottomLatitude;
			this.leftLongitude = leftLongitude;
			this.rightLongitude = rightLongitude;
		}
		
		public boolean isFinished() {
			return finished;
		}
		
		public void finish() {
			finished = true;
			// use downloader callback
			for (IMapDownloaderCallback c : downloader.getDownloaderCallbacks()) {
				c.tileDownloaded(null);
			}
		}

		@Override
		public boolean isCancelled() {
			return cancelled;
		}

		@Override
		public boolean publish(T object) {
			return true;
		}

	}

	protected static class AmenityLoadRequest extends MapObjectLoadRequest<Amenity> {
		private final List<AmenityIndexRepository> res;
		private final PoiFilter filter;
		private final int zoom;

		public AmenityLoadRequest(List<AmenityIndexRepository> repos, int zoom, PoiFilter filter) {
			super();
			this.res = repos;
			this.zoom = zoom;
			this.filter = filter;
		}

		public Runnable prepareToRun() {
			final double ntopLatitude = topLatitude + (topLatitude - bottomLatitude) / 2;
			final double nbottomLatitude = bottomLatitude - (topLatitude - bottomLatitude) / 2;
			final double nleftLongitude = leftLongitude - (rightLongitude - leftLongitude) / 2;
			final double nrightLongitude = rightLongitude + (rightLongitude - leftLongitude) / 2;
			setBoundaries(ntopLatitude, nleftLongitude, nbottomLatitude, nrightLongitude);
			return new Runnable() {
				@Override
				public void run() {
					for (AmenityIndexRepository repository : res) {
						repository.evaluateCachedAmenities(ntopLatitude, nleftLongitude, nbottomLatitude, nrightLongitude, zoom, filter,
								AmenityLoadRequest.this);
					}
					finish();
				}
			};
		}

		public boolean recalculateRequest(AmenityLoadRequest req) {
			if (this.zoom != req.zoom || !Algoritms.objectEquals(this.filter, req.filter)) {
				return true;
			}
			return !isContains(req.topLatitude, req.leftLongitude, req.bottomLatitude, req.rightLongitude);
		}

	}

	protected static class TransportLoadRequest extends MapObjectLoadRequest<TransportStop> {
		private final List<TransportIndexRepository> repos;
		private int zoom;

		public TransportLoadRequest(List<TransportIndexRepository> repos, int zoom) {
			super();
			this.repos = repos;
			this.zoom = zoom;
		}

		public Runnable prepareToRun() {
			final double ntopLatitude = topLatitude + (topLatitude - bottomLatitude) / 2;
			final double nbottomLatitude = bottomLatitude - (topLatitude - bottomLatitude) / 2;
			final double nleftLongitude = leftLongitude - (rightLongitude - leftLongitude) / 2;
			final double nrightLongitude = rightLongitude + (rightLongitude - leftLongitude) / 2;
			setBoundaries(ntopLatitude, nleftLongitude, nbottomLatitude, nrightLongitude);
			return new Runnable() {
				@Override
				public void run() {
					for (TransportIndexRepository repository : repos) {
						repository.evaluateCachedTransportStops(ntopLatitude, nleftLongitude, nbottomLatitude, nrightLongitude, zoom,
								LIMIT_TRANSPORT, TransportLoadRequest.this);
					}
					finish();
				}
			};
		}

		public boolean recalculateRequest(TransportLoadRequest req) {
			if (this.zoom != req.zoom) {
				return true;
			}
			return !isContains(req.topLatitude, req.leftLongitude, req.bottomLatitude, req.rightLongitude);
		}

	}

	protected static class MapLoadRequest {
		public final RotatedTileBox tileBox;

		public MapLoadRequest(RotatedTileBox tileBox) {
			super();
			this.tileBox = tileBox;
		}
	}


}