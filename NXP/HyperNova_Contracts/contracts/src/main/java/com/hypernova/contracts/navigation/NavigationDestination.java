package com.hypernova.contracts.navigation;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * A destination owned and resolved by Navigation.
 *
 * The ID is opaque to NOVA. Search IDs remain valid for at least the contract TTL; saved IDs remain
 * valid while the destination exists.
 */
public final class NavigationDestination implements Parcelable {
    private final String id;
    private final int source;
    private final String title;
    private final String subtitle;
    private final String category;
    private final long distanceMeters;

    public NavigationDestination(
            String id,
            int source,
            String title,
            String subtitle,
            String category,
            long distanceMeters
    ) {
        this.id = id;
        this.source = source;
        this.title = title;
        this.subtitle = subtitle;
        this.category = category;
        this.distanceMeters = distanceMeters;
    }

    private NavigationDestination(Parcel in) {
        id = in.readString();
        source = in.readInt();
        title = in.readString();
        subtitle = in.readString();
        category = in.readString();
        distanceMeters = in.readLong();
    }

    public String getId() {
        return id;
    }

    public int getSource() {
        return source;
    }

    public String getTitle() {
        return title;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public String getCategory() {
        return category;
    }

    /** Returns -1 when distance is not available. */
    public long getDistanceMeters() {
        return distanceMeters;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(id);
        dest.writeInt(source);
        dest.writeString(title);
        dest.writeString(subtitle);
        dest.writeString(category);
        dest.writeLong(distanceMeters);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<NavigationDestination> CREATOR =
            new Creator<NavigationDestination>() {
                @Override
                public NavigationDestination createFromParcel(Parcel in) {
                    return new NavigationDestination(in);
                }

                @Override
                public NavigationDestination[] newArray(int size) {
                    return new NavigationDestination[size];
                }
            };
}
