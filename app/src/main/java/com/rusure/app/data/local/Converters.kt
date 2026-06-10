package com.rusure.app.data.local

import androidx.room.TypeConverter
import com.rusure.app.domain.model.GateAction
import com.rusure.app.domain.model.TargetType

/** Conversores de tipos no primitivos para Room. */
class Converters {

    @TypeConverter
    fun fromTargetType(value: TargetType): String = value.name

    @TypeConverter
    fun toTargetType(value: String): TargetType = TargetType.valueOf(value)

    @TypeConverter
    fun fromGateAction(value: GateAction): String = value.name

    @TypeConverter
    fun toGateAction(value: String): GateAction = GateAction.valueOf(value)
}
