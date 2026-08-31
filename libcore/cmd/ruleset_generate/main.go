package main

import (
	"archive/tar"
	"bytes"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"

	"github.com/sagernet/sing-box/common/geosite"
	"github.com/sagernet/sing-box/common/srs"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common"

	"github.com/klauspost/compress/zstd"
)

var (
	geositeDate = flag.String("geosite", "", "geosite date")
	geoipDate   = flag.String("geoip", "", "geoip date")

	geositeOutput = flag.String("so", "geosite.tar.zst", "geosite tar.zst output")
	geoipOutput   = flag.String("io", "geoip.tar.zst", "geoip tar.zst output")
)

const (
	geositeRepo           = "v2fly/domain-list-community"
	geoipRepo             = "Dreamacro/maxmind-geoip"
	runetFreedomBase      = "https://raw.githubusercontent.com/runetfreedom/russia-v2ray-rules-dat/release/sing-box/rule-set-geosite"
	runetFreedomGeoIPBase = "https://raw.githubusercontent.com/runetfreedom/russia-v2ray-rules-dat/release/sing-box/rule-set-geoip"

	siteName = "dlc.dat"
	ipName   = "Country.mmdb"

	finalBufCap = 524288

	// windowSize defines the zstd compression window size.
	// Using 128KB instead of MaxWindowSize (1GB) to avoid OOM on low-memory devices during decompression.
	//
	// Analysis of actual file sizes in tar archives:
	//   geoip.tar:   237 files, median 633B,  95th percentile 28.62KB,  max 259KB
	//   geosite.tar: 1683 files, median 94B, 95th percentile 1.09KB, max 182KB
	//
	// Since .srs files are already compressed with zlib.BestCompression, they contain high-entropy
	// data that cannot be significantly re-compressed. The 1GB window searches through massive amounts
	// of effectively random data, providing no benefit over a smaller window while requiring 1GB+ memory.
	//
	// 128KB window is sufficient to:
	//   - Cover 4.5x the 95th percentile file size
	//   - Capture patterns across 23-222 average files
	//   - Compress tar headers and metadata effectively
	//   - Decompress with only ~256KB memory (128KB window + 128KB max block)
	//
	// See: https://github.com/xchacha20-poly1305/husi/issues/614
	//      https://github.com/klauspost/compress/discussions/675
	windowSize = 128 << 10 // 128KB
)

var runetFreedomGeositeFiles = []string{
	"geosite-ru-blocked.srs",
	"geosite-ru-blocked-all.srs",
}

var runetFreedomGeoipFiles = []string{
	"geoip-ru-blocked.srs",
	"geoip-ru-blocked-community.srs",
}

func init() {
	flag.Parse()
}

func main() {
	buf := bytes.NewBuffer(nil) // Shared buf.
	buf.Grow(finalBufCap)

	if *geositeDate != "" {
		siteFile, err := os.Create(*geositeOutput)
		if err != nil {
			log.Fatal(err)
		}
		defer siteFile.Close()
		zWriter := common.Must1(newZstdWriter(siteFile))
		defer zWriter.Close()
		tWriter := tar.NewWriter(zWriter)
		defer tWriter.Close()

		geositeData, err := fetch(geositeRepo, *geositeDate, siteName)
		if err != nil {
			log.Fatal(err)
		}
		geosites, err := generateGeosite(geositeData)
		if err != nil {
			log.Fatal(err)
		}
		for _, geositeItem := range geosites.Entries() {
			var headlessRule option.DefaultHeadlessRule
			defaultRule := geosite.Compile(geositeItem.Value)
			headlessRule.Domain = defaultRule.Domain
			headlessRule.DomainSuffix = defaultRule.DomainSuffix
			headlessRule.DomainKeyword = defaultRule.DomainKeyword
			headlessRule.DomainRegex = defaultRule.DomainRegex
			var plainRuleSet option.PlainRuleSet
			plainRuleSet.Rules = []option.HeadlessRule{
				{
					Type:           C.RuleTypeDefault,
					DefaultOptions: headlessRule,
				},
			}
			buf.Reset()
			err = srs.Write(buf, plainRuleSet, C.RuleSetVersionCurrent)
			if err != nil {
				log.Fatal(err)
			}
			srsName := "geosite-" + geositeItem.Key + ".srs"
			// Reproducible builds should not set time.
			err = tWriter.WriteHeader(&tar.Header{
				Name: srsName,
				Size: int64(buf.Len()),
				Mode: int64(os.ModePerm),
			})
			if err != nil {
				log.Fatal(err)
			}
			_, err = tWriter.Write(buf.Bytes())
			if err != nil {
				log.Fatal(err)
			}
		}
		for _, fileName := range runetFreedomGeositeFiles {
			runetRuleSet, err := fetchAbsoluteURL(fmt.Sprintf("%s/%s", runetFreedomBase, fileName))
			if err != nil {
				log.Fatal(err)
			}
			err = writeTarEntry(tWriter, fileName, runetRuleSet)
			if err != nil {
				log.Fatal(err)
			}
		}
	}

	log.Trace("Buf length: ", buf.Len(), " cap: ", buf.Cap())

	if *geoipDate != "" {
		ipFile, err := os.Create(*geoipOutput)
		if err != nil {
			log.Fatal(err)
		}
		defer ipFile.Close()
		zWriter := common.Must1(newZstdWriter(ipFile))
		defer zWriter.Close()
		tWriter := tar.NewWriter(zWriter)
		defer tWriter.Close()

		geoipData, err := fetch(geoipRepo, *geoipDate, ipName)
		if err != nil {
			log.Fatal(err)
		}
		ips, err := parseGeoip(geoipData)
		if err != nil {
			log.Fatal(err)
		}
		for _, ip := range ips.Entries() {
			var headlessRule option.DefaultHeadlessRule
			headlessRule.IPCIDR = make([]string, 0, len(ip.Value))
			for _, cidr := range ip.Value {
				headlessRule.IPCIDR = append(headlessRule.IPCIDR, cidr.String())
			}
			var plainRuleSet option.PlainRuleSet
			plainRuleSet.Rules = []option.HeadlessRule{
				{
					Type:           C.RuleTypeDefault,
					DefaultOptions: headlessRule,
				},
			}
			buf.Reset()
			err = srs.Write(buf, plainRuleSet, C.RuleSetVersionCurrent)
			if err != nil {
				log.Fatal(err)
			}
			srsName := "geoip-" + ip.Key + ".srs"
			err = tWriter.WriteHeader(&tar.Header{
				Name: srsName,
				Size: int64(buf.Len()),
				Mode: int64(os.ModePerm),
			})
			if err != nil {
				log.Fatal(err)
			}
			_, err = tWriter.Write(buf.Bytes())
			if err != nil {
				log.Fatal(err)
			}
		}
		for _, fileName := range runetFreedomGeoipFiles {
			runetRuleSet, err := fetchAbsoluteURL(fmt.Sprintf("%s/%s", runetFreedomGeoIPBase, fileName))
			if err != nil {
				log.Fatal(err)
			}
			err = writeTarEntry(tWriter, fileName, runetRuleSet)
			if err != nil {
				log.Fatal(err)
			}
		}
	}

	log.Trace("Buf length: ", buf.Len(), " cap: ", buf.Cap())
}

func fetch(repo, tag, name string) ([]byte, error) {
	link := "https://github.com/" + repo + "/releases/download/" + tag + "/" + name

	return fetchAbsoluteURL(link)
}

func fetchAbsoluteURL(link string) ([]byte, error) {
	resp, err := http.Get(link)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("fetch %s returned status %d", link, resp.StatusCode)
	}
	return io.ReadAll(resp.Body)
}

func writeTarEntry(tWriter *tar.Writer, name string, content []byte) error {
	err := tWriter.WriteHeader(&tar.Header{
		Name: name,
		Size: int64(len(content)),
		Mode: int64(os.ModePerm),
	})
	if err != nil {
		return err
	}
	_, err = tWriter.Write(content)
	return err
}

func newZstdWriter(writer io.Writer) (*zstd.Encoder, error) {
	return zstd.NewWriter(
		writer,
		zstd.WithEncoderLevel(zstd.SpeedBestCompression),
		zstd.WithWindowSize(windowSize),
	)
}
