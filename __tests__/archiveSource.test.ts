// Blaze Player (Apache-2.0). Added by Blazeinchue.
import {
  ARCHIVE_LICENSE_FILTER,
  buildArchiveSearchUrl,
  identifierFromId,
  isAllowedLicense,
  parseArchiveSearch,
  pickArchiveVideoFile,
  resolveArchiveMetadata,
} from '../src/lib/sources/archive';

describe('Internet Archive source', () => {
  it('only allows public-domain and non-NC Creative Commons licences', () => {
    expect(isAllowedLicense('http://creativecommons.org/licenses/publicdomain/')).toBe(true);
    expect(isAllowedLicense('http://creativecommons.org/publicdomain/mark/1.0/')).toBe(true);
    expect(isAllowedLicense('https://creativecommons.org/publicdomain/zero/1.0/')).toBe(true);
    expect(isAllowedLicense('https://creativecommons.org/licenses/by/4.0/')).toBe(true);
    expect(isAllowedLicense('https://creativecommons.org/licenses/by-sa/3.0/')).toBe(true);
    expect(isAllowedLicense('https://creativecommons.org/licenses/by-nc/4.0/')).toBe(false);
    expect(isAllowedLicense('https://creativecommons.org/licenses/by-nc-sa/2.0/')).toBe(false);
    expect(isAllowedLicense('https://example.com/license')).toBe(false);
    expect(isAllowedLicense(undefined)).toBe(false);
  });

  it('builds an advancedsearch url that always carries the collection + licence filter', () => {
    const url = buildArchiveSearchUrl('night of the living dead');
    expect(url.startsWith('https://archive.org/advancedsearch.php?')).toBe(true);
    const q = decodeURIComponent(url.split('q=')[1].split('&')[0]);
    expect(q).toBe(`(night of the living dead) AND ${ARCHIVE_LICENSE_FILTER}`);
    expect(url).toContain('output=json');
  });

  it('strips query operators so a search cannot widen the filter', () => {
    const url = buildArchiveSearchUrl('x) OR (collection:anything');
    const q = decodeURIComponent(url.split('q=')[1].split('&')[0]);
    expect(q).toBe(`(x collection anything) AND ${ARCHIVE_LICENSE_FILTER}`);
    expect(q).not.toContain(' OR (collection');
  });

  it('parses search docs and drops anything without an allowed licence', () => {
    const items = parseArchiveSearch({
      response: {
        docs: [
          {identifier: 'his_girl_friday', title: 'His Girl Friday', year: 1940, licenseurl: 'http://creativecommons.org/licenses/publicdomain/'},
          {identifier: 'nc_film', title: 'NC', licenseurl: 'https://creativecommons.org/licenses/by-nc/3.0/'},
          {identifier: 'no_licence', title: 'None'},
        ],
      },
    });
    expect(items.map(i => i.id)).toEqual(['archive:his_girl_friday']);
    expect(items[0].year).toBe('1940');
    expect(items[0].poster).toBe('https://archive.org/services/img/his_girl_friday');
  });

  it('prefers the h.264 mp4', () => {
    expect(
      pickArchiveVideoFile([
        {name: 'a_512kb.mp4', format: '512Kb MPEG4', size: '10'},
        {name: 'a.mp4', format: 'h.264', size: '20'},
        {name: 'a.ogv', format: 'Ogg Video'},
      ]),
    ).toEqual({name: 'a.mp4', format: 'h.264'});
    expect(pickArchiveVideoFile([{name: 'a.ogv'}])).toBeNull();
  });

  it('re-checks collection and licence when resolving an item', () => {
    const ok = resolveArchiveMetadata('his_girl_friday', {
      metadata: {title: 'His Girl Friday', collection: ['feature_films'], licenseurl: 'http://creativecommons.org/licenses/publicdomain/'},
      files: [{name: 'his girl friday.mp4', format: 'h.264'}],
    });
    expect(ok.uri).toBe('https://archive.org/download/his_girl_friday/his%20girl%20friday.mp4');
    expect(() =>
      resolveArchiveMetadata('x', {metadata: {collection: ['feature_films'], licenseurl: 'https://creativecommons.org/licenses/by-nc/4.0/'}, files: [{name: 'x.mp4'}]}),
    ).toThrow(/not a public-domain/);
    expect(() =>
      resolveArchiveMetadata('y', {metadata: {collection: ['opensource_movies'], licenseurl: 'http://creativecommons.org/licenses/publicdomain/'}, files: [{name: 'y.mp4'}]}),
    ).toThrow(/not a public-domain/);
  });

  it('validates identifiers', () => {
    expect(identifierFromId('archive:his_girl_friday')).toBe('his_girl_friday');
    expect(() => identifierFromId('archive:../etc')).toThrow();
  });
});
