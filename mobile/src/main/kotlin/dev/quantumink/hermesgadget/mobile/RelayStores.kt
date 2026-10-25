package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.client.EncryptedInboxFile as SharedInboxFile
import dev.quantumink.hermesgadget.client.EncryptedRelayStore as SharedRelayStore
import dev.quantumink.hermesgadget.client.ExecutorRelayScheduler as SharedScheduler
import dev.quantumink.hermesgadget.client.RealRelayFiles as SharedRealFiles
import dev.quantumink.hermesgadget.client.RelayFiles as SharedFiles

// The encrypted Relay stores and the controller thread are shared with the watch through :client.
internal typealias EncryptedRelayStore = SharedRelayStore
internal typealias EncryptedInboxFile = SharedInboxFile
internal typealias RelayFiles = SharedFiles
internal typealias RealRelayFiles = SharedRealFiles
internal typealias ExecutorRelayScheduler = SharedScheduler
