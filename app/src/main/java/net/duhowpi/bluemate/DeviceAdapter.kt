package net.duhowpi.bluemate

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.util.TypedValue

class DeviceAdapter : ListAdapter<NearbyDevice, DeviceAdapter.ViewHolder>(DiffCallback()) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.deviceName)
        val distanceText: TextView = view.findViewById(R.id.deviceDistance)
        val arrowText: TextView = view.findViewById(R.id.deviceArrow)
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

    var onItemClick: ((NearbyDevice) -> Unit)? = null
    var onItemLongClick: ((NearbyDevice) -> Unit)? = null

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = getItem(position)
        holder.itemView.setOnClickListener { onItemClick?.invoke(device) }
        holder.itemView.setOnLongClickListener {
            onItemLongClick?.invoke(device)
            true
        }
        val context = holder.itemView.context
        holder.nameText.text = when {
            device.major >= 0 && device.minor >= 0 -> {
                device.customName ?: DeviceNameGenerator.generate(device.major, device.minor)
            }
            else -> {
                context.getString(
                    R.string.paired_device_label,
                    device.displayName ?: device.address ?: context.getString(R.string.device_unknown)
                )
            }
        }
        holder.distanceText.text = if (device.isInRange && device.distance.isFinite()) {
            context.getString(R.string.distance_format, device.distance)
        } else {
            context.getString(R.string.paired_not_in_range)
        }

        val isStale = System.currentTimeMillis() - device.lastSeen > 15_000L
        val textColor = if (isStale) {
            ContextCompat.getColor(context, R.color.device_stale)
        } else {
            val typedValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
            ContextCompat.getColor(context, typedValue.resourceId)
        }
        holder.nameText.setTextColor(textColor)
        holder.distanceText.setTextColor(textColor)

        val heading = device.compassHeading
        if (heading != null) {
            holder.arrowText.visibility = View.VISIBLE
            holder.arrowText.rotation = heading.toFloat()
            holder.arrowText.setTextColor(textColor)
        } else {
            holder.arrowText.visibility = View.INVISIBLE
        }
    }
}
