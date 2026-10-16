import type { ProviderSwitchWarning } from './ConnectorEditor'

export const MESSAGE_STORE_SWITCH_WARNING: ProviderSwitchWarning = {
  title: 'Switching the message store does not migrate data',
  body: (
    <>
      <p>
        Tenants on the shared store start reading and writing the new store immediately, and it is empty. Existing users,
        conversations and messages stay in the current store and are not copied.
      </p>
      <p>Tenants with a dedicated message store are not affected.</p>
    </>
  ),
  acknowledge: 'I understand that existing conversations will not be visible after the switch.',
  confirmLabel: 'Switch message store',
}

export const TENANT_STORE_SWITCH_WARNING: ProviderSwitchWarning = {
  title: 'Changing this tenant\'s message store does not migrate data',
  body: (
    <p>
      The tenant starts using the new store immediately and it is empty. Existing users, conversations and messages stay
      where they are and are not copied.
    </p>
  ),
  acknowledge: 'I understand this tenant will not see its existing conversations after the change.',
  confirmLabel: 'Change message store',
}
