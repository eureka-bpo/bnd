package bndtools.m2e;

import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.m2e.core.project.IMavenProjectChangedListener;
import org.eclipse.m2e.core.project.MavenProjectChangedEvent;
import org.osgi.framework.FrameworkUtil;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MavenProjectChangedListenersTracker extends
	ServiceTracker<IMavenProjectChangedListener, IMavenProjectChangedListener> implements IMavenProjectChangedListener {

	private static final Logger logger = LoggerFactory.getLogger(MavenProjectChangedListenersTracker.class);

	public MavenProjectChangedListenersTracker() {
		super(FrameworkUtil.getBundle(MavenProjectChangedListenersTracker.class)
			.getBundleContext(), IMavenProjectChangedListener.class, null);
		open();
	}

	/**
	 * Needed in M2E version 2.0
	 */
	public void mavenProjectChanged(List<MavenProjectChangedEvent> events, IProgressMonitor monitor) {
		getTracked().values()
			.stream()
			.forEach(listener -> {
				try {
					IMavenProjectChangedListener.class
						.getMethod("mavenProjectChanged", List.class, IProgressMonitor.class)
						.invoke(listener, events, monitor);
				} catch (Throwable t) {
					logger.error(t.getMessage(), t);
				}
			});
	}

	@Override
	public void mavenProjectChanged(MavenProjectChangedEvent[] events, IProgressMonitor monitor) {
		getTracked().values()
			.stream()
			.forEach(listener -> {
				try {
					listener.mavenProjectChanged(events, monitor);
				} catch (Throwable t) {
					logger.error(t.getMessage(), t);
				}
			});
	}

}
