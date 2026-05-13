package org.xs.headunitlauncher.aap.protocol.messages

import org.xs.headunitlauncher.aap.AapMessage
import org.xs.headunitlauncher.aap.protocol.Channel
import org.xs.headunitlauncher.aap.protocol.proto.Sensors
import com.google.protobuf.Message

open class SensorEvent(val sensorType: Int, proto: Message)
    : AapMessage(Channel.ID_SEN, Sensors.SensorsMsgType.SENSOR_EVENT_VALUE, proto)
