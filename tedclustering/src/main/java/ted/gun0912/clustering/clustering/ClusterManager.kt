package ted.gun0912.clustering.clustering

import android.os.AsyncTask
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ted.gun0912.clustering.BaseBuilder
import ted.gun0912.clustering.MarkerManager
import ted.gun0912.clustering.TedMap
import ted.gun0912.clustering.TedMarker
import ted.gun0912.clustering.clustering.algo.*
import ted.gun0912.clustering.clustering.view.ClusterRenderer
import ted.gun0912.clustering.geometry.TedCameraPosition
import java.util.concurrent.locks.ReentrantReadWriteLock


class ClusterManager<Clustering, C : TedClusterItem, RealMarker, Marker : TedMarker<ImageDescriptor>, Map, ImageDescriptor>(
    builder: BaseBuilder<Clustering, C, RealMarker, Marker, Map, ImageDescriptor>
) {
    val map: TedMap<RealMarker, Marker, ImageDescriptor> = builder.map
    val markerManager: MarkerManager<RealMarker, Marker, ImageDescriptor> = MarkerManager(map)

    val markerMarkerCollection: MarkerManager<RealMarker, Marker, ImageDescriptor>.MarkerCollection =
        markerManager.newCollection()
    val clusterMarkerMarkerCollection: MarkerManager<RealMarker, Marker, ImageDescriptor>.MarkerCollection =
        markerManager.newCollection()

    private var mAlgorithm: ScreenBasedAlgorithm<C> = ScreenBasedAlgorithmAdapter(
        PreCachingAlgorithmDecorator(NonHierarchicalDistanceBasedAlgorithm())
    )
    private val mAlgorithmLock = ReentrantReadWriteLock()
    private var mRenderer: ClusterRenderer<Clustering, C, RealMarker, Marker, Map, ImageDescriptor>
    private var previousCameraPosition: TedCameraPosition? = null
    private var mClusterTask: ClusterTask = ClusterTask()
    private var mClusterCoroutine: Job = Job()
    private val clusterEndedListener = builder.clusterEndedListener

    var algorithm: Algorithm<C>?
        get() = mAlgorithm
        set(algorithm) = if (algorithm is ScreenBasedAlgorithm) {
            setAlgorithm(algorithm)
        } else {
            setAlgorithm(ScreenBasedAlgorithmAdapter(algorithm!!))
        }

    init {
        map.addOnCameraIdleListener(::onCameraIdle)
        mRenderer = ClusterRenderer(builder, this)
        builder.item?.let { addItem(it) }
        builder.items?.let { addItems(it) }

    }

    internal fun onMarkerClick(marker: Marker) {
        markerManager.onMarkerClick(marker)
    }

    fun setAlgorithm(algorithm: ScreenBasedAlgorithm<C>) {
        mAlgorithmLock.writeLock().lock()
        try {
            algorithm.addItems(mAlgorithm.items)

            mAlgorithm = algorithm
        } finally {
            mAlgorithmLock.writeLock().unlock()
        }

        if (mAlgorithm.shouldReClusterOnMapMovement()) {
            mAlgorithm.onCameraChange(map.getCameraPosition())
        }

        cluster()
    }

    private fun internalLockSafe(action: (() -> Unit)) {
        mAlgorithmLock.writeLock().lock()
        try {
            action.invoke()
        } catch (e: Exception) {
            Log.e("ted", e.localizedMessage)
        } finally {
            mAlgorithmLock.writeLock().unlock()
        }
    }

    fun clearItems() {
        internalLockSafe {
            mAlgorithm.clearItems()
        }
    }

    fun addItems(items: Collection<C>) {
        internalLockSafe {
            mAlgorithm.addItems(items)
        }
    }


    fun addItem(myItem: C) {
        internalLockSafe {
            mAlgorithm.addItem(myItem)
        }
    }

    fun removeItem(item: C) {
        internalLockSafe {
            mAlgorithm.removeItem(item)
        }
    }

    /**
     * Force a re-cluster. You may want to call this after adding new item(s).
     */
    fun cluster() {
        internalLockSafe {
            mClusterCoroutine.cancel()
            mClusterCoroutine = CoroutineScope(Dispatchers.IO).launch {
                val zoom = map.getCameraPosition().zoom
                execute(zoom)
                clusterEndedListener?.invoke()
            }
        }

    }

    private fun onCameraIdle(cameraPosition: TedCameraPosition) {

        mAlgorithm.onCameraChange(cameraPosition)

        // delegate clustering to the algorithm
        if (mAlgorithm.shouldReClusterOnMapMovement()) {
            cluster()

            // Don't re-compute clusters if the map has just been panned/tilted/rotated.
        } else if (!isSameZoom(cameraPosition)) {
            previousCameraPosition = cameraPosition
            cluster()
        }
    }

    private fun isSameZoom(cameraPosition: TedCameraPosition) =
        previousCameraPosition?.zoom == cameraPosition.zoom

    /**
     * Runs the clustering algorithm in a background thread, then re-paints when results come back.
     */

    private fun execute(vararg zoom: Double?) {
        mAlgorithmLock.readLock().lock()
        try {
            val clusters = mAlgorithm.getClusters(zoom[0]!!)
            mRenderer.onClustersChanged(clusters)
        } finally {
            mAlgorithmLock.readLock().unlock()
        }
    }

    private inner class ClusterTask : AsyncTask<Double, Void, Set<Cluster<C>>>() {
        override fun doInBackground(vararg zoom: Double?): Set<Cluster<C>> {
            mAlgorithmLock.readLock().lock()
            try {
                return mAlgorithm.getClusters(zoom[0]!!)
            } finally {
                mAlgorithmLock.readLock().unlock()
            }
        }


        override fun onPostExecute(clusters: Set<Cluster<C>>) {
            mRenderer.onClustersChanged(clusters)
        }
    }

}
