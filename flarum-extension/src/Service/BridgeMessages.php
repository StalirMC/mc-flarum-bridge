<?php

namespace Stalir\McBridge\Service;

use Flarum\Locale\Translator;
use Flarum\Settings\SettingsRepositoryInterface;

/**
 * Resolves the language used for everything this extension outputs.
 *
 * The locale is taken from the `mc-bridge.locale` setting and always falls back
 * to Simplified Chinese, regardless of the forum's own default locale, so the
 * extension's console output and API errors are Chinese out of the box.
 *
 * The locale is passed explicitly to the translator instead of being pushed
 * into it with setLocale(), because the translator is a shared singleton and
 * mutating it would leak into the rest of the request.
 */
final class BridgeMessages
{
    /** Top level key used by locale/*.yml, and the translation key prefix. */
    public const PACKAGE = 'stalir-mc-bridge';

    /** Language used when nothing else is configured. */
    public const DEFAULT_LOCALE = 'zh-Hans';

    public const SETTING_KEY = 'mc-bridge.locale';

    private string $locale;

    public function __construct(
        private readonly Translator $translator,
        private readonly SettingsRepositoryInterface $settings
    ) {
        $this->locale = self::resolveLocale($settings);
    }

    public function locale(): string
    {
        return $this->locale;
    }

    /**
     * Translate one of this extension's keys.
     *
     * @param  array<string, string>  $replace  Symfony style %placeholder% pairs.
     */
    public function get(string $suffix, array $replace = []): string
    {
        return $this->translator->get(self::PACKAGE.'.'.$suffix, $replace, $this->locale);
    }

    public static function resolveLocale(SettingsRepositoryInterface $settings): string
    {
        $configured = trim((string) $settings->get(self::SETTING_KEY, ''));

        if ($configured !== '' && self::isAvailable($configured)) {
            return $configured;
        }

        if (self::isAvailable(self::DEFAULT_LOCALE)) {
            return self::DEFAULT_LOCALE;
        }

        return self::availableLocales()[0] ?? 'en';
    }

    /**
     * Languages shipped in locale/, i.e. the values accepted by the setting.
     *
     * @return string[]
     */
    public static function availableLocales(): array
    {
        $files = glob(dirname(__DIR__, 2).'/locale/*.yml') ?: [];

        $locales = array_map(
            static fn (string $file): string => basename($file, '.yml'),
            $files
        );

        sort($locales);

        return array_values($locales);
    }

    public static function isAvailable(string $locale): bool
    {
        return $locale !== '' && in_array($locale, self::availableLocales(), true);
    }
}
