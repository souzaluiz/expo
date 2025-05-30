import rnFixture from '../../plugins/__tests__/fixtures/react-native-project';
import {
  addGoogleMapsAppDelegateImport,
  addGoogleMapsAppDelegateInit,
  addMapsCocoaPods,
  getGoogleMapsApiKey,
  MATCH_INIT,
  removeGoogleMapsAppDelegateImport,
  removeGoogleMapsAppDelegateInit,
  removeMapsCocoaPods,
  setGoogleMapsApiKey,
} from '../Maps';

describe('MATCH_INIT', () => {
  it(`matches React AppDelegate`, () => {
    expect(
      `return super.application(application, didFinishLaunchingWithOptions: launchOptions)`
    ).toMatch(MATCH_INIT);
  });
});

describe(getGoogleMapsApiKey, () => {
  it(`returns null from all getters if no value provided`, () => {
    expect(getGoogleMapsApiKey({})).toBe(null);
  });

  it(`returns the correct values from all getters if a value is provided`, () => {
    expect(getGoogleMapsApiKey({ ios: { config: { googleMapsApiKey: '123' } } })).toBe('123');
  });
});
describe(getGoogleMapsApiKey, () => {
  it(`sets the google maps api key if provided or returns plist`, () => {
    expect(setGoogleMapsApiKey({ ios: { config: { googleMapsApiKey: '123' } } }, {})).toMatchObject(
      {
        GMSApiKey: '123',
      }
    );

    expect(setGoogleMapsApiKey({}, {})).toMatchObject({});
  });
});

describe(addMapsCocoaPods, () => {
  it(`adds maps pods to Podfile`, () => {
    const results = addMapsCocoaPods(rnFixture['ios/Podfile']);
    // matches a static snapshot
    expect(results.contents).toMatchSnapshot();
    expect(results.contents).toMatch(/e9cc66c360abe50bc66d89fffb3c55b034d7d369/);
    // did add new content
    expect(results.didMerge).toBe(true);
    // didn't remove old content
    expect(results.didClear).toBe(false);

    const modded = addMapsCocoaPods(results.contents);
    // nothing changed
    expect(modded.didMerge).toBe(false);
    expect(modded.didClear).toBe(false);

    const modded2 = removeMapsCocoaPods(modded.contents);
    expect(modded2.contents).toBe(rnFixture['ios/Podfile']);
    // didn't add new content
    expect(modded2.didMerge).toBe(false);
    // did remove the generated content
    expect(modded2.didClear).toBe(true);
  });
});

describe(addGoogleMapsAppDelegateImport, () => {
  it(`adds maps import to AppDelegate`, () => {
    const results = addGoogleMapsAppDelegateImport(rnFixture['ios/HelloWorld/AppDelegate.swift']);
    // matches a static snapshot
    expect(results.contents).toMatchSnapshot();
    expect(results.contents).toMatch(/bee50fec513f89284e0fa3f5d935afdde33af98f/);
    // did add new content
    expect(results.didMerge).toBe(true);
    // didn't remove old content
    expect(results.didClear).toBe(false);

    const modded = addGoogleMapsAppDelegateImport(results.contents);
    // nothing changed
    expect(modded.didMerge).toBe(false);
    expect(modded.didClear).toBe(false);

    const modded2 = removeGoogleMapsAppDelegateImport(modded.contents);
    expect(modded2.contents).toBe(rnFixture['ios/HelloWorld/AppDelegate.swift']);
    // didn't add new content
    expect(modded2.didMerge).toBe(false);
    // did remove the generated content
    expect(modded2.didClear).toBe(true);
  });
  it(`fails to add to a malformed podfile`, () => {
    expect(() => addGoogleMapsAppDelegateImport(`foobar`)).toThrow(/foobar/);
  });
});

describe(addGoogleMapsAppDelegateInit, () => {
  it(`adds maps import to AppDelegate`, () => {
    const results = addGoogleMapsAppDelegateInit(
      rnFixture['ios/HelloWorld/AppDelegate.swift'],
      'mykey'
    );
    // matches a static snapshot
    expect(results.contents).toMatchSnapshot();
    expect(results.contents).toMatch(/d167568d212e7a4ec24615c397330e087bc93758/);
    // did add new content
    expect(results.didMerge).toBe(true);
    // didn't remove old content
    expect(results.didClear).toBe(false);

    const modded = addGoogleMapsAppDelegateInit(results.contents, 'mykey');
    // nothing changed
    expect(modded.didMerge).toBe(false);
    expect(modded.didClear).toBe(false);

    // Test that the block is updated when the API key changes
    const modded2 = addGoogleMapsAppDelegateInit(results.contents, 'mykey-2');
    expect(modded2.contents).not.toMatch(/d167568d212e7a4ec24615c397330e087bc93758/);
    expect(modded2.contents).toMatch(/39ee0ad05073499562c15fd671e3d0459ac1c60b/);
    // nothing changed
    expect(modded2.didMerge).toBe(true);
    expect(modded2.didClear).toBe(true);

    const modded3 = removeGoogleMapsAppDelegateInit(modded.contents);
    expect(modded3.contents).toBe(rnFixture['ios/HelloWorld/AppDelegate.swift']);
    // didn't add new content
    expect(modded3.didMerge).toBe(false);
    // did remove the generated content
    expect(modded3.didClear).toBe(true);
  });
  it(`adds maps import to AppDelegate`, () => {
    const results = addGoogleMapsAppDelegateInit(
      rnFixture['ios/HelloWorld/AppDelegate.swift'],
      'mykey'
    );
    // matches a static snapshot
    expect(results.contents).toMatchSnapshot();
    expect(results.contents).toMatch(/d167568d212e7a4ec24615c397330e087bc93758/);
    // did add new content
    expect(results.didMerge).toBe(true);
    // didn't remove old content
    expect(results.didClear).toBe(false);
  });

  it(`fails to add to a malformed app delegate`, () => {
    expect(() => addGoogleMapsAppDelegateInit(`foobar`, 'mykey')).toThrow(/foobar/);
  });
});
