"""Connections in OS credential storage, never a plaintext fallback."""
import json
import sys

SERVICE = 'ru.ryadom.peklo.web'

class VaultError(Exception):
    pass

def backend():
    try:
        if sys.platform == 'darwin':
            from keyring.backends.macOS import Keyring
            return Keyring()
        if sys.platform == 'win32':
            from keyring.backends.Windows import WinVaultKeyring
            return WinVaultKeyring()
    except Exception:
        pass
    raise VaultError('Системное хранилище паролей недоступно. Проверьте установку зависимостей и доступ к хранилищу ОС.')

def load(key):
    try:
        value = backend().get_password(SERVICE, key)
        result = json.loads(value) if value else None
        if result is not None and not isinstance(result, dict):
            raise ValueError()
        return result
    except Exception:
        raise VaultError('Не удалось прочитать сохранённое подключение из хранилища ОС.') from None

def save(key, config):
    try:
        backend().set_password(SERVICE, key, json.dumps(config, ensure_ascii=False))
    except Exception:
        raise VaultError('Не удалось сохранить подключение в хранилище ОС. Разрешите доступ и повторите подключение.') from None

def delete(key):
    try:
        store = backend()
        if store.get_password(SERVICE, key) is not None:
            store.delete_password(SERVICE, key)
    except Exception:
        raise VaultError('Не удалось удалить сохранённое подключение из хранилища ОС. Повторите отключение.') from None
