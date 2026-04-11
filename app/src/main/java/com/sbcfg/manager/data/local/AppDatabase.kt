package com.sbcfg.manager.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.sbcfg.manager.data.local.dao.AppRuleDao
import com.sbcfg.manager.data.local.dao.CustomDomainDao
import com.sbcfg.manager.data.local.dao.ServerConfigDao
import com.sbcfg.manager.data.local.entity.AppRuleEntity
import com.sbcfg.manager.data.local.entity.CustomDomainEntity
import com.sbcfg.manager.data.local.entity.ServerConfigEntity

@Database(
    entities = [ServerConfigEntity::class, CustomDomainEntity::class, AppRuleEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverConfigDao(): ServerConfigDao
    abstract fun customDomainDao(): CustomDomainDao
    abstract fun appRuleDao(): AppRuleDao
}
