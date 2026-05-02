package net.duhowpi.bluemate

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class DeviceAdapter : ListAdapter<NearbyDevice, DeviceAdapter.ViewHolder>(DiffCallback()) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.deviceName)
        val distanceText: TextView = view.findViewById(R.id.deviceDistance)
    }

    private class DiffCallback : DiffUtil.ItemCallback<NearbyDevice>() {
        override fun areItemsTheSame(oldItem: NearbyDevice, newItem: NearbyDevice): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: NearbyDevice, newItem: NearbyDevice): Boolean =
            oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = getItem(position)
        val context = holder.itemView.context
        holder.nameText.text = if (device.major >= 0 && device.minor >= 0) {
            context.getString(R.string.device_label, device.major, device.minor)
        } else {
            context.getString(
                R.string.paired_device_label,
                device.displayName ?: device.address ?: context.getString(R.string.device_unknown)
            )
        }
        holder.distanceText.text = if (device.isInRange && device.distance.isFinite()) {
            context.getString(R.string.distance_format, device.distance)
        } else {
            context.getString(R.string.paired_not_in_range)
        }
    }
}
