package eu.siacs.conversations.ui.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.location.Location;

import androidx.core.content.ContextCompat;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.util.TileSystem;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.mylocation.SimpleLocationOverlay;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;

public class MyLocation extends SimpleLocationOverlay {
	private final GeoPoint position;
	private final float accuracy;
	private final Point mapCenterPoint;
	private final Paint fill;
	private final Paint outline;

	public MyLocation(final Context ctx, final Bitmap icon, final Location position) {
		super(icon);
		this.mapCenterPoint = new Point();
		this.fill = new Paint(Paint.ANTI_ALIAS_FLAG);
		final int accent = ContextCompat.getColor(ctx,R.color.blue500);
		fill.setColor(accent);
		fill.setStyle(Paint.Style.FILL);
		this.outline = new Paint(Paint.ANTI_ALIAS_FLAG);
		outline.setColor(accent);
		outline.setAlpha(50);
		outline.setStyle(Paint.Style.FILL);
		this.position = new GeoPoint(position);
		this.accuracy = position.getAccuracy();
	}

	@Override
	public void draw(final Canvas c, final MapView view, final boolean shadow) {
		super.draw(c, view, shadow);

		view.getProjection().toPixels(position, mapCenterPoint);
		c.drawCircle(mapCenterPoint.x, mapCenterPoint.y,
				Math.max(Config.Map.MY_LOCATION_INDICATOR_SIZE + Config.Map.MY_LOCATION_INDICATOR_OUTLINE_SIZE,
						accuracy / (float) TileSystem.GroundResolution(position.getLatitude(), view.getZoomLevel())
				), this.outline);
		c.drawCircle(mapCenterPoint.x, mapCenterPoint.y, Config.Map.MY_LOCATION_INDICATOR_SIZE, this.fill);
	}
}
